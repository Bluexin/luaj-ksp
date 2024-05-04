package be.bluexin.luajksp.generators

import be.bluexin.luajksp.*
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import be.bluexin.luajksp.annotations.LuajMapped
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.isOpen
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.MemberName.Companion.member
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
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
            .addFunction(
                FunSpec.builder("typename")
                    .returns(String::class)
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("return %S", forDeclaration.simpleName.asString())
                    .build()
            )
            .addFunctions(listOf(getter, setter)).apply {
                frozenFunctionWrappers.forEach { (name, type) ->
                    this.addFunctionWrapper(forDeclaration, name, type, wrapped, frozenFunctionWrappers)
                }
                properties.values.asSequence().mapNotNull { it as? ExposedFunction }.forEach {
                    addKtFunctionWrapper(it.declaration, wrapped, functionWrappers)
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
            }.build()
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

        val (call, extras) = luaToKotlin(it.source, "value", type, wrapped, functionWrappers)

        addStatement("%S -> %N.%L = $call", it.simpleName, wrapped, it.simpleName, *extras.toTypedArray())
    }

    private fun luaToKotlin(
        context: KSNode,
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
            extras.removeFirst()
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
                        extras += wrapperName
                        extras += receiver
                        extras += wrapperName
                        extras += receiver
                        "if (%L is K2L%N) %L.ktFunction else %N(%L.checkfunction())"
                    } else error("Functions frozen", context)
                } else {
                    val typeDeclaration = type.declaration
                    if (typeDeclaration.isExposed) {
                        extras += typeDeclaration.accessClassName
                        extras += typeDeclaration.accessClassName
                        extras += wrapped
                        "(%L.checkuserdata(%T::class.java) as %T).%N"
                    } else type.unsupportedTypeError(context)
                }
            }
        }

        return "$nullability$call" to extras
    }

    private fun TypeSpec.Builder.addFunctionWrapper(
        context: KSNode,
        name: String,
        type: KSType,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ) {
        val args = type.arguments.take(type.arguments.size - 1) // removing return type
        val isLuaVararg = args.size > 3
        val returnType = type.arguments.last().type!!.resolve()
        val isReturnUnit = returnType.declaration.qualifiedName?.asString() == "kotlin.Unit"

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
                            val luaArgs = mutableListOf<String>()
                            args.forEachIndexed { index, arg ->
                                addParameter("arg$index", arg.toTypeName())
                                val (call, extras) = kotlinToLua(
                                    context,
                                    "arg$index",
                                    arg.type!!.resolve(),
                                    functionWrappers
                                )
                                val luaArg = "luaArg$index"
                                luaArgs += luaArg
                                addStatement("val $luaArg = $call", *extras.toTypedArray())
                            }

                            addStatement(
                                "val ret = %N.invoke(%M(arrayOf(${luaArgs.joinToString()}))).arg1()",
                                luaFunction, LuaVarargsOfName,
                            )

                            if (isReturnUnit) addStatement("return Unit")
                            else {
                                val (retCall, extras) = luaToKotlin(
                                    context,
                                    "ret",
                                    returnType,
                                    wrapped,
                                    functionWrappers
                                )
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
                .superclass(type.toLuaFnSuperType(context))
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter(ktFunction.name, ktFunction.type)
                        .build()
                )
                .addProperty(ktFunction)
                .addFunction(
                    FunSpec.builder(if (isLuaVararg) "invoke" else "call")
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(LuaValueClassName).apply {
                            val ktArgs = mutableListOf<String>()

                            if (isLuaVararg) addParameter("args", LuaVarargsClassName)
                            args.forEachIndexed { index, arg ->
                                if (!isLuaVararg) addParameter("arg$index", LuaValueClassName)
                                val (call, extras) = luaToKotlin(
                                    context,
                                    if (isLuaVararg) "args.arg(${index + 1})" else "arg$index",
                                    arg.type!!.resolve(),
                                    wrapped,
                                    functionWrappers
                                )
                                val ktArg = "luaArg$index"
                                ktArgs += ktArg
                                addStatement("val $ktArg = $call", *extras.toTypedArray())
                            }

                            addStatement("val ret = %N(${ktArgs.joinToString()})", ktFunction)

                            if (isReturnUnit) addStatement("return %M", LuaValueClassName.member("NONE"))
                            else {
                                val (retCall, extras) = kotlinToLua(context, "ret", returnType, functionWrappers)
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
                                        decl,
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

                            addStatement(
                                "val ret = %N.%L(${ktArgs.joinToString()})",
                                wrapped,
                                decl.simpleName.asString()
                            )

                            val returnType = decl.returnType!!.resolve()

                            if (returnType.declaration.qualifiedName?.asString() == "kotlin.Unit") addStatement(
                                "return %M", LuaValueClassName.member("NONE")
                            ) else {
                                val (retCall, extras) = kotlinToLua(decl, "ret", returnType, functionWrappers)
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
        val (call, extras) = kotlinToLua(
            it.source,
            "${wrapped.name}.${it.simpleName}",
            it.type.resolve(),
            functionWrappers
        )
        addStatement("%S -> $call", it.simpleName, *extras.toTypedArray())
    }

    private fun FunSpec.Builder.addGetFunction(
        fn: KSFunctionDeclaration
    ) {
        addStatement("%1S -> %1L%2L()", fn.simpleName.asString(), "Wrapper")
    }

    private fun kotlinToLua(
        context: KSNode,
        receiver: String,
        type: KSType,
        functionWrappers: Map<String, KSType>
    ): Pair<String, List<Any>> {
        val extras = mutableListOf<Any>()

        val customMapper = (type.annotations + type.declaration.annotations).firstOrNull {
            it.shortName.asString() == "LuajMapped" && it.annotationType.resolve().declaration
                .qualifiedName?.asString() == LuajMapped::class.qualifiedName
        }

        fun call(nestedReceiver: String): String = if (customMapper != null) {
            val mapper = customMapper.arguments.first { it.name?.asString() == "mapper" }.value as KSType
            extras += mapper.toTypeName()
            extras += nestedReceiver
            when (val ck = (mapper.declaration as KSClassDeclaration).classKind) {
                ClassKind.OBJECT -> "%T.toLua(%L)"
                ClassKind.CLASS -> "%T().toLua(%L)"
                else -> error("Unsupported class kind : $ck", context)
            }
        } else when (type.declaration.simpleName.getShortName()) {
            "String", "Int", "Boolean", "Double" -> {
                extras += LuaValueOfName
                extras += nestedReceiver
                "%M(%L)"
            }

            "Long" -> {
                extras += CoerceJavaToLuaName
                extras += nestedReceiver
                "%M(%L)"
            }

            else -> {
                if (type.isFunctionType) {
                    logger.warn("Found function type", type.declaration)
                    val wrapperName = type.functionWrapperName
                    if (wrapperName in functionWrappers) {
                        extras += nestedReceiver
                        extras += wrapperName
                        extras += wrapperName
                        extras += nestedReceiver
                        "(%L as? %N)?.luaFunction ?: K2L%N(%L)"
                    } else {
                        extras += MemberName("kotlin", "TODO")
                        extras += "No wrapper found for $type (expected $wrapperName)"
                        "%M(%S)"
                    }
                } else {
                    var call: String? = null
                    val typeDeclaration = type.declaration
                    if (typeDeclaration is KSClassDeclaration) {
                        val superTypes = typeDeclaration.getAllSuperTypes()

                        if (superTypes.any { it.toClassName() == KotlinIterableName }) {
                            extras += LuaTableOfName
                            extras += nestedReceiver

                            val bound = type.arguments.singleOrNull()?.type?.resolve()
                                ?: error("Expected a single argument type", context)

                            if (bound.declaration.isOpen() && bound.declaration.let { it !is KSClassDeclaration || it.getAllSuperTypes().none { t -> t.toClassName() == LKExposedName } }) {
                                logger.warn("Exposing open type that does not implement $LKExposedName, this will not follow inheritance !", context)
                            }

                            val (nestedCall, nestedExtras) = kotlinToLua(
                                context,
                                "element",
                                bound,
                                functionWrappers
                            )

                            extras.addAll(nestedExtras)

                            call = "%M(emptyArray(), %L.map { element -> $nestedCall }.toTypedArray())"
                        } else if (superTypes.any { it.toClassName() == LKExposedName }) {
                            extras += nestedReceiver
                            call = "%L.toLua()"
                        }
                    }

                    if (call == null && typeDeclaration.isExposed) {
                        extras += typeDeclaration.accessClassName
                        extras += nestedReceiver
                        call = "%T(%L)"
                    }

                    call ?: type.unsupportedTypeError(context)
                }
            }
        }

        val withNullability = if (type.nullability == Nullability.NULLABLE) {
            extras += receiver
            val call = call("notNil")
            extras += LuaValueClassName.member("NIL")
            "%L?.let { notNil -> $call } ?: %M"
        } else call(receiver)

        return withNullability to extras
    }

    private val KSAnnotated.isExposed
        get() = isAnnotationPresent(LuajExpose::class) ||
                isAnnotationPresent(LuajExposeExternal::class)

    private fun KSType.unsupportedTypeError(context: KSNode): Nothing = error("Unsupported type $this", context)

    private fun error(message: String, at: KSNode): Nothing {
        logger.error(message, at)
        error(message)
    }

    private fun KSType.toLuaFnSuperType(context: KSNode): ClassName = when {
        !isFunctionType -> error("Expected Function type", context)
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
