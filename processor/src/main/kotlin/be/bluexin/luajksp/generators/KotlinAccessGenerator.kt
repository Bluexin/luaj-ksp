package be.bluexin.luajksp.generators

import be.bluexin.luajksp.*
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import be.bluexin.luajksp.annotations.LuajMapped
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.isOpen
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.MemberName.Companion.member
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

@OptIn(KspExperimental::class)
internal class KotlinAccessGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {

    fun generate(
        forDeclaration: KSDeclaration,
        properties: Map<String, ExposedData>
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
                properties.values.filter { it is ExposedPropertyLike && it.hasSetter }.forEach {
                    addLuaToKotlin(it as ExposedPropertyLike, wrapped, functionWrappers)
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
                properties.values.filter { it is ExposedPropertyLike && it.hasGetter }.forEach {
                    addGetProperty(it as ExposedPropertyLike, wrapped, frozenFunctionWrappers)
                }
                properties.values.mapNotNull { it as? ExposedFunction }.forEach {
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
            .addFunctions(listOf(getter, setter)).apply {
                frozenFunctionWrappers.forEach { (name, type) ->
                    this.addFunctionWrapper(name, type, wrapped, frozenFunctionWrappers)
                }
                properties.values.asSequence().mapNotNull { it as? ExposedFunction }.forEach {
                    addKtFunctionWrapper(it.declaration, wrapped, functionWrappers)
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
        it: ExposedPropertyLike,
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
            val mapper = customMapper.arguments.first { it.name?.asString() == "mapper" }.value as KSType
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
                        extras += receiver
                        extras += wrapperName
                        extras += receiver
                        extras += wrapperName
                        extras += receiver
                        "if (%L is K2L%N) %L.ktFunction else %N(%L.checkfunction())"
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
    private fun TypeSpec.Builder.addFunctionWrapper(
        name: String,
        type: KSType,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ) {
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

        val ktFunction = PropertySpec.builder("ktFunction", type.toTypeName())
            .initializer("ktFunction")
            .build()

        addType(
            TypeSpec.classBuilder("K2L$name")
                .addModifiers(KModifier.PRIVATE)
                .superclass(type.toLuaFnSuperType())
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter(ktFunction.name, ktFunction.type)
                        .build()
                )
                .addProperty(ktFunction)
                .addFunction(
                    FunSpec.builder("call")
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(LuaValueClassName).apply {
                            val args = type.arguments.take(type.arguments.size - 1) // removing return type
                            val ktArgs = mutableListOf<String>()
                            if (args.size > 3) {
                                TODO("Arity > 3 not yet supported (wrap with varargs)")
                            } else {
                                args.forEachIndexed { index, arg ->
                                    addParameter("arg$index", LuaValueClassName)
                                    val (call, extras) = luaToKotlin(
                                        "arg$index",
                                        arg.type!!.resolve(),
                                        wrapped,
                                        functionWrappers
                                    )
                                    val ktArg = "luaArg$index"
                                    ktArgs += ktArg
                                    addStatement("val $ktArg = $call", *extras.toTypedArray())
                                }
                            }

                            addStatement("val ret = %N(${ktArgs.joinToString()})", ktFunction)

                            val returnType = type.arguments.last().type!!.resolve()

                            if (returnType.declaration.qualifiedName?.asString() == "kotlin.Unit") addStatement(
                                "return %M", LuaValueClassName.member("NONE")
                            ) else {
                                val (retCall, extras) = kotlinToLua("ret", returnType, functionWrappers)
                                addStatement("return $retCall", *extras.toTypedArray())
                            }
                        }.build()
                ).build()
        )
    }

    private fun TypeSpec.Builder.addKtFunctionWrapper(
        decl: KSFunctionDeclaration,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ) {
        addType(
            TypeSpec.classBuilder("${decl.simpleName.asString()}Wrapper")
                .addModifiers(KModifier.PRIVATE, KModifier.INNER)
                .superclass(decl.toLuaFnSuperType())
                .addFunction(
                    FunSpec.builder("call")
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(LuaValueClassName).apply {
                            val ktArgs = mutableListOf<String>()
                            if (decl.parameters.size > 3) {
                                TODO()
                            } else {
                                decl.parameters.forEachIndexed { index, arg ->
                                    addParameter("arg$index", LuaValueClassName)
                                    val (call, extras) = luaToKotlin(
                                        "arg$index",
                                        arg.type.resolve(),
                                        wrapped,
                                        functionWrappers
                                    )
                                    val ktArg = "luaArg$index"
                                    ktArgs += ktArg
                                    addStatement("val $ktArg = $call", *extras.toTypedArray())
                                }
                            }

                            addStatement("val ret = %N.%L(${ktArgs.joinToString()})", wrapped, decl.simpleName.asString())

                            val returnType = decl.returnType!!.resolve()

                            if (returnType.declaration.qualifiedName?.asString() == "kotlin.Unit") addStatement(
                                "return %M", LuaValueClassName.member("NONE")
                            ) else {
                                val (retCall, extras) = kotlinToLua("ret", returnType, functionWrappers)
                                addStatement("return $retCall", *extras.toTypedArray())
                            }
                        }.build()
                ).build()
        )
    }

    private fun FunSpec.Builder.addGetProperty(
        it: ExposedPropertyLike,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ) {
        val (call, extras) = kotlinToLua("${wrapped.name}.${it.simpleName}", it.type.resolve(), functionWrappers)
        addStatement("%S -> $call", it.simpleName, *extras.toTypedArray())
    }

    private fun FunSpec.Builder.addGetFunction(
        fn: KSFunctionDeclaration
    ) {
        addStatement("%1S -> %1L%2L()", fn.simpleName.asString(), "Wrapper")
    }

    private fun kotlinToLua(
        receiver: String,
        type: KSType,
        functionWrappers: Map<String, KSType>
    ): Pair<String, List<Any>> {
        val extras = mutableListOf<Any>()
        val nullability = if (type.nullability == Nullability.NULLABLE) {
            extras += receiver
            extras += LuaValueClassName.member("NIL")
            "if (%L == null) %M else "
        } else ""

        val customMapper = (type.annotations + type.declaration.annotations).firstOrNull {
            it.shortName.asString() == "LuajMapped" && it.annotationType.resolve().declaration
                .qualifiedName?.asString() == LuajMapped::class.qualifiedName
        }
        val call = if (customMapper != null) {
            val mapper = customMapper.arguments.first { it.name?.asString() == "mapper" }.value as KSType
            extras.clear()
            extras += mapper.toTypeName()
            extras += receiver
            when (val ck = (mapper.declaration as KSClassDeclaration).classKind) {
                ClassKind.OBJECT -> "%T.toLua(%L)"
                ClassKind.CLASS -> "%T().toLua(%L)"
                else -> error("Unsupported class kind : $ck", customMapper)
            }
        } else when (type.declaration.simpleName.getShortName()) {
            "String", "Int", "Boolean", "Double" -> {
                extras += LuaValueOfName
                extras += receiver
                "%M(%L)"
            }

            "Long" -> {
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
                        extras += wrapperName
                        extras += receiver
                        "(%L as? %N)?.luaFunction ?: K2L%N(%L)"
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

        return "$nullability$call" to extras
    }

    private val KSAnnotated.isExposed
        get() = isAnnotationPresent(LuajExpose::class) ||
                isAnnotationPresent(LuajExposeExternal::class)

    private fun KSType.unsupportedTypeError(): Nothing = error("Unsupported type $this", declaration)

    private fun error(message: String, at: KSNode): Nothing {
        logger.error(message, at)
        error(message)
    }

    private fun KSType.toLuaFnSuperType(): ClassName = when {
        !isFunctionType -> error("Expected Function type", this.declaration)
        else -> when (arguments.size) {
            1 -> ZeroArgFunctionName
            2 -> OneArgFunctionName
            3 -> TwoArgFunctionName
            4 -> ThreeArgFunctionName
            else -> VarArgFunctionName
        }
    }

    private fun KSFunctionDeclaration.toLuaFnSuperType(): ClassName = when (parameters.size) {
        0 -> ZeroArgFunctionName
        1 -> OneArgFunctionName
        2 -> TwoArgFunctionName
        3 -> ThreeArgFunctionName
        else -> VarArgFunctionName
    }
}
