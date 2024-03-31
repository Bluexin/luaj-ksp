package be.bluexin.luajksp.generators

import be.bluexin.luajksp.*
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import be.bluexin.luajksp.annotations.LuajMapped
import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import org.intellij.lang.annotations.Language
import java.io.OutputStream

@OptIn(KspExperimental::class)
internal class KotlinAccessGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {

    fun generate(
        forDeclaration: KSDeclaration,
        properties: Map<String, PropertyLike>
    ) {
        val receiverClassName = forDeclaration.simpleName.asString()

        val target = forDeclaration.accessClassName

        val parentName = (if (forDeclaration is KSClassDeclaration) {
            forDeclaration.superTypes
                .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
                .singleOrNull {
                    it.classKind == ClassKind.CLASS && it.isAnnotationPresent(LuajExpose::class)
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
            .beginControlFlow("when (key.checkjstring())").apply {
                properties.values.filter(PropertyLike::hasSetter).forEach {
                    addLuaToKotlin(it, wrapped, functionWrappers)
                }
                addStatement(
                    "else -> " +
                            if (parentName == LuaUserdataClassName) "error(\"Cannot set \$key on \${javaClass.simpleName}\")"
                            else "super.set(key, value)"
                )
            }.endControlFlow().build()

        val frozenFunctionWrappers = functionWrappers.toMap()
        val getter = FunSpec.builder("get")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("key", LuaValueClassName)
            .returns(LuaValueClassName)
            .beginControlFlow("return when (key.checkjstring())").apply {
                properties.values.filter(PropertyLike::hasGetter).forEach {
                    addGetProperty(it, wrapped, frozenFunctionWrappers)
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
            .addFunctions(listOf(getter, setter)).apply {
                frozenFunctionWrappers.forEach { (name, type) ->
                    this.addFunctionWrapper(name, type, wrapped, frozenFunctionWrappers)
                }
            }.addOriginatingKSFile(forDeclaration.containingFile!!)
            .build()

        FileSpec.builder(target)
            .indent("    ")
            .addType(accessClass)
            .addFunction(
                FunSpec.builder("toLua")
                    .receiver(receiverType)
                    .returns(target)
                    .addStatement("return %T(this)", target)
                    .build()
            ).build()
            .writeTo(codeGenerator, true)
    }

    private val KSType.functionWrapperName
        get() =
            this.declaration.simpleName.getShortName() + arguments.joinToString(separator = "") {
                it.type!!.resolve().declaration.simpleName.getShortName()
            } + "Wrapper"

    private fun FunSpec.Builder.addLuaToKotlin(
        it: PropertyLike,
        wrapped: PropertySpec,
        functionWrappers: MutableMap<String, KSType>
    ) {
        val typeRef = it.type
        val type = typeRef.resolve()
        logger.logging("Processing $typeRef (resolved to `$type`) for $this", typeRef)

        val (call, extras) = luaToKotlin("value", type, wrapped, functionWrappers)

        addStatement("%S -> %N.%L = $call", it.simpleName, wrapped, it.simpleName, *extras.toTypedArray())
    }

    private fun luaToKotlin(
        receiver: String,
        type: KSType,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ): Pair<String, List<Any>> {
        val extras = mutableListOf<Any>(receiver)
        val nullability = if (type.nullability == Nullability.NULLABLE) {
            extras += receiver
            "if (%L.isnil()) null else "
        } else ""

        val customMapper = (type.annotations + type.declaration.annotations).firstOrNull {
            it.shortName.asString() == "LuajMapped" && it.annotationType.resolve().declaration
                .qualifiedName?.asString() == LuajMapped::class.qualifiedName
        }
        val call = if (customMapper != null) {
            val mapper  = customMapper.arguments.first { it.name?.asString() == "mapper" }.value as KSType
            extras.clear()
            extras += mapper.toTypeName()
            extras += receiver
            when (val ck = (mapper.declaration as KSClassDeclaration).classKind) {
                ClassKind.OBJECT -> "%T.fromLua(%L.checknotnil())"
                ClassKind.CLASS -> "%T().fromLua(%L.checknotnil())"
                else -> error("Unsupported class kind : $ck", customMapper)
            }
        } else when (type.declaration.simpleName.getShortName()) {
            "String" -> "%L.checkjstring()"
            "Int" -> "%L.checkint()"
            "Long" -> "%L.checklong()"
            "Boolean" -> "%L.checkboolean()"
            "Double" -> "%L.checkdouble()"

            else -> {
                if (type.isFunctionType) {
                    logger.warn("Found function type", type.declaration)
                    if (functionWrappers is MutableMap) {
                        val wrapperName = type.functionWrapperName
                        functionWrappers[wrapperName] = type
                        extras.clear()
                        extras += wrapperName
                        extras += receiver
                        "%N(%L.checkfunction())"
                    } else error("Functions frozen", type.declaration)
                } else {
                    val typeDeclaration = type.declaration
                    if (typeDeclaration.isExposed) {
                        extras += typeDeclaration.accessClassName
                        extras += typeDeclaration.accessClassName
                        extras += wrapped
                        "(%L.checkuserdata(%T::class.java) as %T).%N"
                    } else type.unsupportedTypeError()
                }
            }
        }

        return "$nullability$call" to extras
    }

    // TODO : support receiver function ?
    private fun TypeSpec.Builder.addFunctionWrapper(name: String, type: KSType, wrapped: PropertySpec, functionWrappers: Map<String, KSType>) {
        val luaFunction = PropertySpec.builder("luaFunction", LuaFunctionClassName)
            .initializer("luaFunction")
            .build()

        addType(
            TypeSpec.classBuilder(name)
                .addModifiers(KModifier.PRIVATE)
                .addSuperinterface(type.toTypeName())
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter(luaFunction.name, luaFunction.type)
                        .build()
                )
                .addProperty(luaFunction)
                .addFunction(
                    FunSpec.builder("invoke")
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(type.arguments.last().toTypeName()).apply {
                            val args = type.arguments.take(type.arguments.size - 1) // removing return type
                            val luaArgs = mutableListOf<String>()
                            args.forEachIndexed { index, arg ->
                                addParameter("arg$index", arg.toTypeName())
                                val (call, extras) = kotlinToLua("arg$index", arg.type!!.resolve(), functionWrappers)
                                val luaArg = "luaArg$index"
                                luaArgs += luaArg
                                addStatement("val $luaArg = $call", *extras.toTypedArray())
                            }

                            addStatement(
                                "val ret = %N.invoke(%M(arrayOf(${luaArgs.joinToString()}))).arg1()",
                                luaFunction, LuaVarargsOfName,
                            )

                            val returnType = type.arguments.last().type!!.resolve()

                            if (returnType.declaration.qualifiedName?.asString() == "kotlin.Unit") addStatement("return Unit")
                            else {
                                val (retCall, extras) = luaToKotlin("ret", returnType, wrapped, functionWrappers)
                                addStatement("return $retCall", *extras.toTypedArray())
                            }
                        }.build()
                ).build()
        )
    }

    private fun FunSpec.Builder.addGetProperty(
        it: PropertyLike,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ) {
        val (call, extras) = kotlinToLua("${wrapped.name}.${it.simpleName}", it.type.resolve(), functionWrappers)
        addStatement("%S -> $call", it.simpleName, *extras.toTypedArray())
    }

    // TODO : add a way to define arbitrary (code-driven) mapping
    private fun kotlinToLua(
        receiver: String,
        type: KSType,
        functionWrappers: Map<String, KSType>
    ): Pair<String, List<Any>> {
        val extras = mutableListOf<Any>()

        val customMapper = (type.annotations + type.declaration.annotations).firstOrNull {
            it.shortName.asString() == "LuajMapped" && it.annotationType.resolve().declaration
                .qualifiedName?.asString() == LuajMapped::class.qualifiedName
        }
        val call = if (customMapper != null) {
            val mapper  = customMapper.arguments.first { it.name?.asString() == "mapper" }.value as KSType
            extras.clear()
            extras += mapper.toTypeName()
            extras += receiver
            when (val ck = (mapper.declaration as KSClassDeclaration).classKind) {
                ClassKind.OBJECT -> "%T.toLua(%L)"
                ClassKind.CLASS -> "%T().toLua(%L)"
                else -> error("Unsupported class kind : $ck", customMapper)
            }
        } else when (type.declaration.simpleName.getShortName()) {
            "String", "Int", "Long", "Boolean", "Double" -> {
                extras += CoerceJavaToLuaName
                extras += receiver
                "%M(%L)"
            }

            else -> {
                if (type.isFunctionType) {
                    logger.warn("Found function type", type.declaration)
                    val wrapperName = type.functionWrapperName
                    if (wrapperName in functionWrappers) {
                        extras += receiver
                        extras += wrapperName
                        extras += "Exposing pure KFunction to lua is not yet implemented"
                        "(%L as? %N)?.luaFunction ?: error(%S)"
                    } else {
                        extras += MemberName("kotlin", "TODO")
                        extras += "No wrapper found for $type (expected $wrapperName)"
                        "%M(%S)"
                    }
                } else {
                    val typeDeclaration = type.declaration
                    if (typeDeclaration.isExposed) {
                        extras += typeDeclaration.accessClassName
                        extras += receiver
                        "%T(%L)"
                    } else type.unsupportedTypeError()
                }
            }
        }

        return call to extras
    }

    private val KSAnnotated.isExposed
        get() = isAnnotationPresent(LuajExpose::class) ||
                isAnnotationPresent(LuajExposeExternal::class)

    private fun KSType.unsupportedTypeError(): Nothing = error("Unsupported type $this", declaration)

    private fun error(message: String, at: KSNode): Nothing {
        logger.error(message, at)
        error(message)
    }

    private fun OutputStream.appendKotlin(@Language("kt") str: String) = this.write(str.toByteArray())
}
