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
        } else luaToKotlinSimpleMapping(type.declaration) ?: run {
            if (type.isFunctionType) {
                logger.warn("Found function type", type.declaration)
                if (functionWrappers is MutableMap) {
                    val wrapperName = type.functionWrapperName()
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

        return "$nullability$call" to extras
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

    fun addGetProperty(
        builder: FunSpec.Builder,
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
        builder.addStatement("%S -> $call", it.simpleName, *extras.toTypedArray())
    }

    fun kotlinToLua(
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

            "Long", "Float" -> {
                extras += LuaValueOfName
                extras += nestedReceiver
                "%M(%L.toDouble())"
            }

            else -> {
                if (type.isFunctionType) {
                    val wrapperName = type.functionWrapperName()
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
                        // Separated from supertypes as supertypes is expensive
                        when (typeDeclaration.toClassName()) {
                            LKExposedName -> {
                                extras += nestedReceiver
                                call = "%L.toLua()"
                            }

                            LuaValueClassName -> {
                                extras += nestedReceiver
                                call = "%L"
                            }

                            else -> {
                                val superTypes = typeDeclaration.getAllSuperTypes()

                                when {
                                    superTypes.any { it.toClassName() == KotlinIterableName } -> {
                                        extras += LuaTableOfName
                                        extras += nestedReceiver

                                        val bound = type.arguments.singleOrNull()?.type?.resolve()
                                            ?: error("Expected a single argument type", context)

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

                                        val (nestedCall, nestedExtras) = kotlinToLua(
                                            context,
                                            "element",
                                            bound,
                                            functionWrappers
                                        )

                                        extras.addAll(nestedExtras)

                                        call = "%M(emptyArray(), %L.map { element -> $nestedCall }.toTypedArray())"
                                    }

                                    superTypes.any { it.toClassName() == LKExposedName } -> {
                                        extras += nestedReceiver
                                        call = "%L.toLua()"
                                    }

                                    superTypes.any { it.toClassName() == LuaValueClassName } -> {
                                        extras += nestedReceiver
                                        call = "%L"
                                    }
                                }
                            }
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