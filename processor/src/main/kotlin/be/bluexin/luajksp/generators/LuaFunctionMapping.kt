package be.bluexin.luajksp.generators

import be.bluexin.luajksp.*
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import be.bluexin.luajksp.annotations.LuajMapped
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.isOpen
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.MemberName.Companion.member
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName

/**
 * Shared code generation helpers for converting Kotlin values to/from Lua and for
 * emitting the function wrapper classes used both by generated LuaUserdata access
 * wrappers ([KotlinAccessGenerator]) and generated Lua library modules ([LuaLibGenerator]).
 */
@OptIn(KspExperimental::class)
internal class LuaFunctionMapping(
    private val logger: KSPLogger
) {

    fun KSType.functionWrapperName(): String =
        this.declaration.simpleName.getShortName() + arguments.joinToString(separator = "") {
            it.type!!.resolve().declaration.simpleName.getShortName()
        } + "Wrapper"

    fun luaToKotlin(
        context: KSNode,
        receiver: String,
        type: KSType,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ): CodeBlock {
        val customMapper = (type.annotations + type.declaration.annotations).firstOrNull {
            it.shortName.asString() == "LuajMapped" && it.annotationType.resolve().declaration
                .qualifiedName?.asString() == LuajMapped::class.qualifiedName
        }

        val body: CodeBlock = if (customMapper != null) {
            val mapper = customMapper.arguments.first { it.name?.asString() == "mapper" }.value as KSType
            when (val ck = (mapper.declaration as KSClassDeclaration).classKind) {
                ClassKind.OBJECT -> CodeBlock.of("%T.fromLua(%L.checknotnil())", mapper.toTypeName(), receiver)
                ClassKind.CLASS -> CodeBlock.of("%T().fromLua(%L.checknotnil())", mapper.toTypeName(), receiver)
                else -> error("Unsupported class kind : $ck", customMapper)
            }
        } else luaToKotlinSimpleMapping(type.declaration)?.let { CodeBlock.of(it, receiver) } ?: run {
            if (type.isFunctionType) {
                logger.warn("Found function type", type.declaration)
                if (functionWrappers is MutableMap) {
                    val wrapperName = type.functionWrapperName()
                    functionWrappers[wrapperName] = type
                    CodeBlock.of(
                        "if (%L is K2L%N) %L.ktFunction else %N(%L.checkfunction())",
                        receiver, wrapperName, receiver, wrapperName, receiver
                    )
                } else error("Functions frozen", context)
            } else {
                val typeDeclaration = type.declaration
                if (typeDeclaration is KSClassDeclaration && typeDeclaration.isMapType()) {
                    val keyBound = type.arguments.getOrNull(0)?.type?.resolve()
                        ?: error("Expected a key type argument", context)
                    val valueBound = type.arguments.getOrNull(1)?.type?.resolve()
                        ?: error("Expected a value type argument", context)

                    val keyBlock = luaToKotlin(context, "key", keyBound, wrapped, functionWrappers)
                    val valueBlock = luaToKotlin(context, "t.get(key)", valueBound, wrapped, functionWrappers)

                    CodeBlock.of(
                        "%L.checktable().let { t -> t.keys().associate { key -> %L to %L } }",
                        receiver, keyBlock, valueBlock
                    )
                } else if (typeDeclaration.isExposed) {
                    CodeBlock.of(
                        "(%L.checkuserdata(%T::class.java) as %T).%N",
                        receiver, typeDeclaration.accessClassName, typeDeclaration.accessClassName, wrapped
                    )
                } else type.unsupportedTypeError(context)
            }
        }

        return if (type.nullability == Nullability.NULLABLE)
            CodeBlock.of("if (%L.isnil()) null else %L", receiver, body)
        else body
    }

    fun addFunctionWrapper(
        builder: TypeSpec.Builder,
        context: KSNode,
        name: String,
        type: KSType,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ) {
        // These wrapper classes always hold a definite, non-null function value - nullability of the
        // *property* they back is handled by the caller (luaToKotlin/kotlinToLua's own null-checking
        // prefix), before a wrapper instance is ever constructed or referenced. Using the property's
        // possibly-nullable `type` directly here would emit a nullable supertype (illegal in Kotlin)
        // and a nullable `ktFunction` field invoked without `?.invoke()` (also illegal).
        val nonNullType = type.makeNotNullable()

        val args = type.arguments.take(type.arguments.size - 1) // removing return type
        val isLuaVararg = args.size > 3
        val returnType = type.arguments.last().type!!.resolve()
        val isReturnUnit = returnType.declaration.qualifiedName?.asString() == "kotlin.Unit"

        val luaFunction = PropertySpec.builder("luaFunction", LuaFunctionClassName)
            .initializer("luaFunction")
            .build()

        builder.addType(
            TypeSpec.classBuilder(name)
                .addModifiers(KModifier.PRIVATE)
                .addSuperinterface(nonNullType.toTypeName())
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
                                val block = kotlinToLua(
                                    context,
                                    "arg$index",
                                    arg.type!!.resolve(),
                                    functionWrappers
                                )
                                val luaArg = "luaArg$index"
                                luaArgs += luaArg
                                addStatement("val %L = %L", luaArg, block)
                            }

                            addStatement(
                                "val ret = %N.invoke(%M(arrayOf(${luaArgs.joinToString()}))).arg1()",
                                luaFunction, LuaVarargsOfName,
                            )

                            if (isReturnUnit) addStatement("return Unit")
                            else {
                                val block = luaToKotlin(
                                    context,
                                    "ret",
                                    returnType,
                                    wrapped,
                                    functionWrappers
                                )
                                addStatement("return %L", block)
                            }
                        }.build()
                ).build()
        )

        val ktFunction = PropertySpec.builder("ktFunction", nonNullType.toTypeName())
            .initializer("ktFunction")
            .build()

        builder.addType(
            TypeSpec.classBuilder("K2L$name")
                .addModifiers(KModifier.PRIVATE)
                .superclass(nonNullType.toLuaFnSuperType(context))
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
                                val block = luaToKotlin(
                                    context,
                                    if (isLuaVararg) "args.arg(${index + 1})" else "arg$index",
                                    arg.type!!.resolve(),
                                    wrapped,
                                    functionWrappers
                                )
                                val ktArg = "luaArg$index"
                                ktArgs += ktArg
                                addStatement("val %L = %L", ktArg, block)
                            }

                            addStatement("val ret = %N(${ktArgs.joinToString()})", ktFunction)

                            if (isReturnUnit) addStatement("return %M", LuaValueClassName.member("NONE"))
                            else {
                                val block = kotlinToLua(context, "ret", returnType, functionWrappers)
                                addStatement("return %L", block)
                            }
                        }.build()
                ).build()
        )
    }

    fun addKtFunctionWrapper(
        builder: TypeSpec.Builder,
        decl: KSFunctionDeclaration,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ) {
        builder.addType(
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
                                    val block = luaToKotlin(
                                        decl,
                                        "arg$index",
                                        arg.type.resolve(),
                                        wrapped,
                                        functionWrappers
                                    )
                                    val ktArg = "luaArg$index"
                                    ktArgs += ktArg
                                    addStatement("val %L = %L", ktArg, block)
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
                                val block = kotlinToLua(decl, "ret", returnType, functionWrappers)
                                addStatement("return %L", block)
                            }
                        }.build()
                ).build()
        )
    }

    fun addGetProperty(
        builder: FunSpec.Builder,
        it: ExposedPropertyLike,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ) {
        val block = kotlinToLua(
            it.source,
            "${wrapped.name}.${it.simpleName}",
            it.type.resolve(),
            functionWrappers
        )
        builder.addStatement("%S -> %L", it.simpleName, block)
    }

    fun kotlinToLua(
        context: KSNode,
        receiver: String,
        type: KSType,
        functionWrappers: Map<String, KSType>
    ): CodeBlock {
        val customMapper = (type.annotations + type.declaration.annotations).firstOrNull {
            it.shortName.asString() == "LuajMapped" && it.annotationType.resolve().declaration
                .qualifiedName?.asString() == LuajMapped::class.qualifiedName
        }

        fun call(nestedReceiver: String): CodeBlock = if (customMapper != null) {
            val mapper = customMapper.arguments.first { it.name?.asString() == "mapper" }.value as KSType
            when (val ck = (mapper.declaration as KSClassDeclaration).classKind) {
                ClassKind.OBJECT -> CodeBlock.of("%T.toLua(%L)", mapper.toTypeName(), nestedReceiver)
                ClassKind.CLASS -> CodeBlock.of("%T().toLua(%L)", mapper.toTypeName(), nestedReceiver)
                else -> error("Unsupported class kind : $ck", context)
            }
        } else when (type.declaration.simpleName.getShortName()) {
            "String", "Int", "Boolean", "Double" -> CodeBlock.of("%M(%L)", LuaValueOfName, nestedReceiver)

            "Long", "Float" -> CodeBlock.of("%M(%L.toDouble())", LuaValueOfName, nestedReceiver)

            else -> {
                if (type.isFunctionType) {
                    val wrapperName = type.functionWrapperName()
                    if (wrapperName in functionWrappers) {
                        CodeBlock.of(
                            "(%L as? %N)?.luaFunction ?: K2L%N(%L)",
                            nestedReceiver, wrapperName, wrapperName, nestedReceiver
                        )
                    } else {
                        CodeBlock.of(
                            "%M(%S)",
                            MemberName("kotlin", "TODO"),
                            "No wrapper found for $type (expected $wrapperName)"
                        )
                    }
                } else {
                    var call: CodeBlock? = null
                    val typeDeclaration = type.declaration
                    if (typeDeclaration is KSClassDeclaration) {
                        // Separated from supertypes as supertypes is expensive
                        when (typeDeclaration.toClassName()) {
                            LKExposedName -> call = CodeBlock.of("%L.toLua()", nestedReceiver)

                            LuaValueClassName -> call = CodeBlock.of("%L", nestedReceiver)

                            else -> {
                                val superTypes = typeDeclaration.getAllSuperTypes()

                                when {
                                    superTypes.any { it.toClassName() == KotlinIterableName } -> {
                                        val bound = type.arguments.singleOrNull()?.type?.resolve()
                                            ?: error("Expected a single argument type", context)

                                        warnIfOpenNotExposed(bound, context)

                                        val nested = kotlinToLua(context, "element", bound, functionWrappers)

                                        call = CodeBlock.of(
                                            "%M(emptyArray(), %L.map { element -> %L }.toTypedArray())",
                                            LuaTableOfName, nestedReceiver, nested
                                        )
                                    }

                                    typeDeclaration.isMapType() -> {
                                        val keyBound = type.arguments.getOrNull(0)?.type?.resolve()
                                            ?: error("Expected a key type argument", context)
                                        val valueBound = type.arguments.getOrNull(1)?.type?.resolve()
                                            ?: error("Expected a value type argument", context)

                                        warnIfOpenNotExposed(keyBound, context)
                                        warnIfOpenNotExposed(valueBound, context)

                                        val keyBlock = kotlinToLua(context, "entry.key", keyBound, functionWrappers)
                                        val valueBlock =
                                            kotlinToLua(context, "entry.value", valueBound, functionWrappers)

                                        // LuaTable's (keys, values) constructor does *not* take parallel
                                        // key/value arrays: the first array is a flat, interleaved
                                        // [key0, value0, key1, value1, ...] list (as for a `{[k]=v, ...}`
                                        // table constructor), and the second is a plain positional/array
                                        // part - which we don't use here.
                                        call = CodeBlock.of(
                                            "%M(%L.entries.flatMap { entry -> listOf(%L, %L) }" +
                                                    ".toTypedArray(), emptyArray())",
                                            LuaTableOfName, nestedReceiver, keyBlock, valueBlock
                                        )
                                    }

                                    superTypes.any { it.toClassName() == LKExposedName } ->
                                        call = CodeBlock.of("%L.toLua()", nestedReceiver)

                                    superTypes.any { it.toClassName() == LuaValueClassName } ->
                                        call = CodeBlock.of("%L", nestedReceiver)
                                }
                            }
                        }
                    }

                    if (call == null && typeDeclaration.isExposed) {
                        call = CodeBlock.of("%T(%L)", typeDeclaration.accessClassName, nestedReceiver)
                    }

                    call ?: type.unsupportedTypeError(context)
                }
            }
        }

        return if (type.nullability == Nullability.NULLABLE) {
            val body = call("notNil")
            CodeBlock.of("%L?.let { notNil -> %L } ?: %M", receiver, body, LuaValueClassName.member("NIL"))
        } else call(receiver)
    }

    private fun warnIfOpenNotExposed(bound: KSType, context: KSNode) {
        if (bound.declaration.isOpen() && bound.declaration.let {
                it !is KSClassDeclaration || (
                        it.toClassName() != LKExposedName &&
                                it.getAllSuperTypes()
                                    .none { t -> t.toClassName() == LKExposedName }
                        )
            }) {
            logger.warn(
                "Exposing open type that does not implement $LKExposedName, this will not follow inheritance !",
                context
            )
        }
    }

    private val KSAnnotated.isExposed
        get() = isAnnotationPresent(LuajExpose::class) ||
                isAnnotationPresent(LuajExposeExternal::class)

    private fun KSType.unsupportedTypeError(context: KSNode): Nothing = error("Unsupported type $this", context)

    private fun luaToKotlinSimpleMapping(typeDecl: KSDeclaration): String? {
        return when (typeDecl.simpleName.getShortName()) {
            "String" -> "%L.checkjstring()"
            "Int" -> "%L.checkint()"
            "Long" -> "%L.checklong()"
            "Boolean" -> "%L.checkboolean()"
            "Double" -> "%L.checkdouble()"
            "Float" -> "%L.checkdouble().toFloat()"
            else -> if (typeDecl is KSClassDeclaration) {
                when (typeDecl.toClassName()) {
                    LuaValueClassName -> "%L"
                    LuaTableClassName -> "%L.checktable()"
                    LuaFunctionClassName -> "%L.checkfunction()"
                    else -> null
                }
            } else null
        }
    }

    private fun error(message: String, at: KSNode): Nothing {
        logger.error(message, at)
        error(message)
    }

    fun KSType.toLuaFnSuperType(context: KSNode): ClassName = when {
        !isFunctionType -> error("Expected Function type", context)
        else -> when (arguments.size) {
            1 -> ZeroArgFunctionName
            2 -> OneArgFunctionName
            3 -> TwoArgFunctionName
            4 -> ThreeArgFunctionName
            else -> VarArgFunctionName
        }
    }

    fun KSFunctionDeclaration.toLuaFnSuperType(): ClassName = when (parameters.size) {
        0 -> ZeroArgFunctionName
        1 -> OneArgFunctionName
        2 -> TwoArgFunctionName
        3 -> ThreeArgFunctionName
        else -> VarArgFunctionName
    }
}
