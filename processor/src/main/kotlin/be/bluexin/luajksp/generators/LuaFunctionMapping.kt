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
                    if (typeDeclaration.isMutableMapType()) {
                        error(
                            "Accepting a MutableMap from Lua is not supported: values received from " +
                                    "Lua are always a disconnected copy, so mutating a MutableMap " +
                                    "parameter/setter would never be observed by the caller - use Map " +
                                    "instead",
                            context
                        )
                    }

                    val keyBound = type.arguments.getOrNull(0)?.type?.resolve()
                        ?: error("Expected a key type argument", context)
                    val valueBound = type.arguments.getOrNull(1)?.type?.resolve()
                        ?: error("Expected a value type argument", context)

                    buildMapFromLuaValue(context, receiver, keyBound, valueBound, wrapped, functionWrappers)
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

    /**
     * Converts an incoming Lua value - a plain table or an existing [be.bluexin.luajksp.annotations.MapAccess] -
     * into a fresh, disconnected `Map<K, V>` snapshot. Shared by [luaToKotlin]'s own map handling
     * (function parameters, property setters) and [buildMutableMapReplace] (assigning a table/MapAccess
     * to a getter-only `MutableMap` property, which clears and repopulates the live backing map rather
     * than replacing the reference).
     */
    private fun buildMapFromLuaValue(
        context: KSNode,
        receiver: String,
        keyBound: KSType,
        valueBound: KSType,
        wrapped: PropertySpec,
        functionWrappers: Map<String, KSType>
    ): CodeBlock {
        val keyBlock = luaToKotlin(context, "key", keyBound, wrapped, functionWrappers)
        val valueFromTableBlock = luaToKotlin(context, "v.get(key)", valueBound, wrapped, functionWrappers)
        val valueFromAccessBlock = luaToKotlin(context, "v.rawGet(key)", valueBound, wrapped, functionWrappers)

        // The trailing `as Map<K, V>` works around the Kotlin compiler inferring this `when`'s
        // type as plain `Any` (rather than the associate{} calls' actual `Map<K, V>`) specifically
        // when this expression ends up nested inside a class extending LuaUserdata, as every
        // generated access/wrapper class does.
        return CodeBlock.builder()
            .add("(")
            .beginControlFlow("when (val v = %L)", receiver)
            .add(
                "is %T -> v.keys().associate { key -> %L to %L }\n",
                LuaTableClassName, keyBlock, valueFromTableBlock
            )
            .add(
                "is %T<*, *> -> v.keys().associate { key -> %L to %L }\n",
                MapAccessClassName, keyBlock, valueFromAccessBlock
            )
            .add("else -> error(\"Expected a table or MapAccess, got \$v\")\n")
            .endControlFlow()
            .add(" as %T<%T, %T>)", KotlinMapName, keyBound.toTypeName(), valueBound.toTypeName())
            .build()
    }

    /**
     * Builds the `"propName" -> ...` branch body for assigning a table/MapAccess to a getter-only
     * `MutableMap<K, V>` property (`t.element.someMap = {...}`) - there's no Kotlin setter to call, so
     * this clears and repopulates the live backing map in place instead of replacing the reference.
     */
    fun buildMutableMapReplace(
        context: KSNode,
        wrapped: PropertySpec,
        propName: String,
        type: KSType,
        functionWrappers: Map<String, KSType>
    ): CodeBlock {
        val keyBound = type.arguments.getOrNull(0)?.type?.resolve()
            ?: error("Expected a key type argument", context)
        val valueBound = type.arguments.getOrNull(1)?.type?.resolve()
            ?: error("Expected a value type argument", context)

        requireNonNullMapBounds(keyBound, valueBound, type, context)

        val convertedMap = buildMapFromLuaValue(context, "value", keyBound, valueBound, wrapped, functionWrappers)

        return CodeBlock.of("%N.%L.also { m -> m.clear(); m.putAll(%L) }", wrapped, propName, convertedMap)
    }

    private fun requireNonNullMapBounds(keyBound: KSType, valueBound: KSType, type: KSType, context: KSNode) {
        if (keyBound.nullability == Nullability.NULLABLE || valueBound.nullability == Nullability.NULLABLE) {
            error(
                "Exposed MutableMap key and value types must be non-null: native Lua table semantics " +
                        "use nil to mean both \"missing\" and \"remove\", so a nullable key or value " +
                        "would be ambiguous - found $type",
                context
            )
        }
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
                                    functionWrappers,
                                    wrapped
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
                                val block = kotlinToLua(context, "ret", returnType, functionWrappers, wrapped)
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
                                val block = kotlinToLua(decl, "ret", returnType, functionWrappers, wrapped)
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
            functionWrappers,
            wrapped
        )
        builder.addStatement("%S -> %L", it.simpleName, block)
    }

    fun kotlinToLua(
        context: KSNode,
        receiver: String,
        type: KSType,
        functionWrappers: Map<String, KSType>,
        wrapped: PropertySpec
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

                                        val nested = kotlinToLua(context, "element", bound, functionWrappers, wrapped)

                                        call = CodeBlock.of(
                                            "%M(emptyArray(), %L.map { element -> %L }.toTypedArray())",
                                            LuaTableOfName, nestedReceiver, nested
                                        )
                                    }

                                    typeDeclaration.isMapType() -> {
                                        if (!typeDeclaration.isMutableMapType()) {
                                            error(
                                                "Exposing a read-only Map to Lua is not supported: " +
                                                        "scripts can't tell a disposable snapshot apart " +
                                                        "from a live MutableMap, which is exactly the " +
                                                        "confusion this convention avoids - use " +
                                                        "MutableMap instead",
                                                context
                                            )
                                        }

                                        val keyBound = type.arguments.getOrNull(0)?.type?.resolve()
                                            ?: error("Expected a key type argument", context)
                                        val valueBound = type.arguments.getOrNull(1)?.type?.resolve()
                                            ?: error("Expected a value type argument", context)

                                        requireNonNullMapBounds(keyBound, valueBound, type, context)

                                        warnIfOpenNotExposed(keyBound, context)
                                        warnIfOpenNotExposed(valueBound, context)

                                        val keyToLua = kotlinToLua(context, "k", keyBound, functionWrappers, wrapped)
                                        val keyFromLua = luaToKotlin(context, "lv", keyBound, wrapped, functionWrappers)
                                        val valueToLua =
                                            kotlinToLua(context, "v", valueBound, functionWrappers, wrapped)
                                        val valueFromLua =
                                            luaToKotlin(context, "lv", valueBound, wrapped, functionWrappers)

                                        // Materializes a live MapAccess rather than a snapshot LuaTable:
                                        // every Map exposed to Lua is required to be a MutableMap (see
                                        // the error above), so get/set on the returned handle always
                                        // affect the real backing map.
                                        call = CodeBlock.of(
                                            "%T(%L, %T.Codec({ k -> %L }, { lv -> %L }), " +
                                                    "%T.Codec({ v -> %L }, { lv -> %L }))",
                                            MapAccessClassName, nestedReceiver,
                                            MapAccessClassName, keyToLua, keyFromLua,
                                            MapAccessClassName, valueToLua, valueFromLua,
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
