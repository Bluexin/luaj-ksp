package be.bluexin.luajksp.generators

import be.bluexin.luajksp.*
import be.bluexin.luajksp.annotations.LuajExpose
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.isOpen
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

@OptIn(KspExperimental::class)
internal class KotlinAccessGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {
    private val mapping = LuaFunctionMapping(logger)

    fun generate(
        forDeclaration: KSDeclaration,
        properties: Map<String, ExposedData>
    ) {
        val receiverClassName = forDeclaration.simpleName.asString()
        val superTypes = (forDeclaration as? KSClassDeclaration)?.getAllSuperTypes()?.toList().orEmpty()
        val hasBeforeSet = superTypes.any { t -> t.toClassName() == BeforeSetName }
        val hasAfterSet = superTypes.any { t -> t.toClassName() == AfterSetName }

        val target = forDeclaration.accessClassName

        val parentName = (if (forDeclaration is KSClassDeclaration) {
            forDeclaration.superTypes
                .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
                .singleOrNull {
                    it.isAnnotationPresent(LuajExpose::class)
                }?.accessClassName
        } else null) ?: LuaUserdataClassName

        logger.info("Generating $target for $receiverClassName", forDeclaration)
        logger.logging("Properties : $properties")

        val receiverType = when (forDeclaration) {
            is KSClassDeclaration -> forDeclaration.asStarProjectedType().toTypeName()
            is KSTypeAlias -> forDeclaration.type.toTypeName()

            else -> error("Unknown declaration type: $forDeclaration", forDeclaration)
        }

        val wrapped = PropertySpec.builder("wrapped", receiverType).apply {
            if (parentName != LuaUserdataClassName) addModifiers(KModifier.OVERRIDE)
            if (forDeclaration.isOpen()) addModifiers(KModifier.OPEN)
        }.initializer("wrapped").build()

        val functionWrappers = mutableMapOf<String, KSType>()
        val setter = FunSpec.builder("set")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("key", LuaValueClassName)
            .addParameter("value", LuaValueClassName)
            .apply {
                if (hasAfterSet) beginControlFlow("try")
                if (hasBeforeSet) addStatement("${wrapped.name}.beforeSet()")
            }
            .beginControlFlow("when (key.checkjstring())").apply {
                properties.values.filter { it is ExposedPropertyLike && it.hasSetter }.forEach {
                    addLuaToKotlin(it as ExposedPropertyLike, wrapped, functionWrappers)
                }
                addStatement(
                    "else -> " +
                            if (parentName == LuaUserdataClassName) "error(\"Cannot set \$key on \${javaClass.simpleName}\")"
                            else "super.set(key, value)"
                )
            }.endControlFlow()
            .apply {
                if (hasAfterSet) nextControlFlow("finally")
                    .addStatement("${wrapped.name}.afterSet()")
                    .endControlFlow()
            }.build()

        val frozenFunctionWrappers = functionWrappers.toMap()
        val getter = FunSpec.builder("get")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("key", LuaValueClassName)
            .returns(LuaValueClassName)
            .beginControlFlow("return when (key.checkjstring())").apply {
                properties.values.filter { it is ExposedPropertyLike && it.hasGetter }.forEach {
                    mapping.addGetProperty(this, it as ExposedPropertyLike, wrapped, frozenFunctionWrappers)
                }
                properties.values.filterIsInstance<ExposedFunction>().forEach {
                    addGetFunction(it.declaration)
                }
                addStatement(
                    "else -> " +
                            if (parentName == LuaUserdataClassName) "error(\"Cannot get \$key on \${javaClass.simpleName}\")"
                            else "super.get(key)"
                )
            }.endControlFlow().build()

        val accessClass = TypeSpec.classBuilder(target)
            .addKdoc("Generated with luaj-ksp").apply {
                if (forDeclaration.isOpen()) addModifiers(KModifier.OPEN)
            }.superclass(parentName)
            .addSuperclassConstructorParameter(wrapped.name)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(wrapped.name, wrapped.type)
                    .build()
            ).addProperty(wrapped)
            .addFunction(
                FunSpec.builder("typename")
                    .returns(String::class)
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("return %S", forDeclaration.simpleName.asString())
                    .build()
            )
            .addFunctions(listOf(getter, setter)).apply {
                frozenFunctionWrappers.forEach { (name, type) ->
                    mapping.addFunctionWrapper(this, forDeclaration, name, type, wrapped, frozenFunctionWrappers)
                }
                properties.values.asSequence().mapNotNull { it as? ExposedFunction }.forEach {
                    mapping.addKtFunctionWrapper(this, it.declaration, wrapped, functionWrappers)
                }
            }.addOriginatingKSFile(forDeclaration.containingFile!!)
            .build()

        FileSpec.builder(target)
            .indent("    ")
            .addType(accessClass).apply {
                if (forDeclaration !is KSClassDeclaration || forDeclaration.getAllSuperTypes()
                        .none { it.toClassName() == LKExposedName }
                ) {
                    addFunction(
                        FunSpec.builder("toLua")
                            .receiver(receiverType)
                            .returns(target)
                            .addStatement("return %T(this)", target)
                            .build()
                    )
                }
            }.build().writeTo(codeGenerator, true)
    }

    private fun FunSpec.Builder.addLuaToKotlin(
        it: ExposedPropertyLike,
        wrapped: PropertySpec,
        functionWrappers: MutableMap<String, KSType>
    ) {
        val typeRef = it.type
        val type = typeRef.resolve()
        logger.logging("Processing $typeRef (resolved to `$type`) for $this", typeRef)

        val (call, extras) = mapping.luaToKotlin(it.source, "value", type, wrapped, functionWrappers)

        addStatement("%S -> %N.%L = $call", it.simpleName, wrapped, it.simpleName, *extras.toTypedArray())
    }

    private fun FunSpec.Builder.addGetFunction(
        fn: KSFunctionDeclaration
    ) {
        addStatement("%1S -> %1L%2L()", fn.simpleName.asString(), "Wrapper")
    }

    private fun error(message: String, at: KSNode): Nothing {
        logger.error(message, at)
        error(message)
    }
}