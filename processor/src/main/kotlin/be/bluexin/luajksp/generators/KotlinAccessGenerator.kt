package be.bluexin.luajksp.generators

import be.bluexin.luajksp.AccessClassFQN
import be.bluexin.luajksp.LuaUserdataFQN
import be.bluexin.luajksp.PropertyLike
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.isOpen
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
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

        val targetFqn = AccessClassFQN(forDeclaration)
        val targetClassName = targetFqn.getShortName()
        val targetPackage = targetFqn.getQualifier()

        val parentClass = (if (forDeclaration is KSClassDeclaration) {
            forDeclaration.superTypes
                .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
                .singleOrNull {
                    it.classKind == ClassKind.CLASS && it.isAnnotationPresent(LuajExpose::class)
                }?.let(::AccessClassFQN)
        } else null) ?: LuaUserdataFQN

        logger.info("Generating $targetPackage.$targetClassName for $receiverClassName", forDeclaration)
        logger.logging("Properties : $properties")

        codeGenerator.createNewFile(
            Dependencies(true, forDeclaration.containingFile!!),
            targetPackage,
            targetClassName
        ).use { file ->
            @Suppress("RedundantSuppression", "ConvertToStringTemplate")
            file.appendKotlin(
                """
                package $targetPackage

                import ${parentClass.asString()}
                import org.luaj.vm2.LuaValue
                import org.luaj.vm2.lib.jse.CoerceJavaToLua
                
                import ${forDeclaration.qualifiedName?.asString() ?: "ERROR"}

                /**
                 * Generated with luaj-ksp
                 */
                @Suppress("RedundantSuppression", "ConvertToStringTemplate")
                ${if (forDeclaration.isOpen()) "open " else ""}class $targetClassName(
                    ${if (parentClass == LuaUserdataFQN) "" else "override "}${if (forDeclaration.isOpen()) "open " else ""}val receiver: $receiverClassName
                ): ${parentClass.getShortName()}(receiver, /*TODO: figure out metatable?*/) {
                
                    override fun set(key: LuaValue, value: LuaValue) {
                        when (key.checkjstring()) {
                        ${
                    properties.values.filter(PropertyLike::hasSetter)
                        .joinToString(separator = "\n                        ") {
                            val sn = it.simpleName
                            """    "$sn" -> receiver.$sn = ${"value".luaValueToKotlin(it)}"""
                        }
                }
                            else -> ${if (parentClass == LuaUserdataFQN) "error(\"Cannot set \$key on $receiverClassName\")" else "super.set(key, value)"} 
                        }
                    }

                    override fun get(key: LuaValue): LuaValue = when (key.checkjstring()) {
                    ${
                    properties.values.filter(PropertyLike::hasGetter)
                        .joinToString(separator = "\n                    ") {
                            "    ${it.javaToLua()}"
                        }
                }
                        else ->  ${if (parentClass == LuaUserdataFQN) "error(\"Cannot get \$key on $receiverClassName\")" else "super.get(key)"}
                    }
                }

                fun $receiverClassName.toLua() = $targetClassName(this)

            """.trimIndent()
            )
        }
    }

    private fun String.luaValueToKotlin(prop: PropertyLike): String =
        luaValueToKotlin(prop.type) ?: prop.unsupportedTypeError()

    private fun String.luaValueToKotlin(typeRef: KSTypeReference): String? {
        val type = typeRef.resolve()
        val receiver = if (type.nullability == Nullability.NOT_NULL) "$this.checknotnil()" else this
        logger.logging("Processing $typeRef (resolved to `$type`) for $this", typeRef)
        return buildString {
            if (type.nullability == Nullability.NULLABLE) append("if (${this@luaValueToKotlin}.isnil()) null else ")

            when (typeRef.toString()) {
                "String" -> append(receiver).append(".checkjstring()")
                "Int" -> append(receiver).append(".checkint()")
                "Long" -> append(receiver).append(".checklong()")
                "Boolean" -> append(receiver).append(".checkboolean()")
                "Double" -> append(receiver).append(".checkdouble()")
                else -> {
                    if (type.isFunctionType) luaFunctionToKotlin(type, receiver, typeRef)
                    else {
                        val typeDeclaration = type.declaration
                        if (typeDeclaration.isExposed || typeRef.isExposed) {
                            val typeFqn = typeDeclaration.accessFqn
                            append('(')
                            append(receiver)
                            append(".checkuserdata(")
                            append(typeFqn)
                            append("::class.java) as ")
                            append(typeFqn)
                            append(").receiver")
                        } else return null
                    }
                }
            }
        }
    }

    private fun StringBuilder.luaFunctionToKotlin(
        type: KSType,
        receiver: String,
        typeRef: KSTypeReference
    ) {
        logger.warn("Found function type $type")
        append(receiver)
        logger.warn("Decl : ${type.declaration}")
        logger.warn("Args : ${type.arguments}")
        append(".checkfunction()")
        append(".let { fn -> { ")
        val args = type.arguments.take(type.arguments.size - 1) // removing return type
        args.forEachIndexed { index, arg ->
            append("arg")
            append(index)
            if (index < args.size - 1) append(", ")
        }
        if (args.isNotEmpty()) append(" -> ")
        val lambda = buildString {
            append("fn.invoke(")
            if (args.isNotEmpty()) {
                append("LuaValue.varargsOf(")
                args.forEachIndexed { index, arg ->
                    val argType =
                        arg.type ?: error("Unable to resolve type of arg\$$index of Function type $type", typeRef)
                    append(argType.javaToLua("arg$index"))
                    if (index < args.size - 1) append(", ")
                }
                append(')')
            }
            append(").arg(1)") // single return value
        }
        append(
            lambda.luaValueToKotlin(
                args.last().type ?: error("Unable to resolve return type of Function type $type", typeRef)
            )
        )
        append(" } }")
    }

    private fun PropertyLike.javaToLua(): String {
        val j2l = type.javaToLua("receiver.$simpleName") ?: unsupportedTypeError()
        return "\"$simpleName\" -> $j2l"
    }

    private fun KSTypeReference.javaToLua(receiver: String): String? {
        return when (toString()) {
            "String", "Int", "Long", "Boolean", "Double" -> "CoerceJavaToLua.coerce($receiver)"
            else -> {
                val type = resolve()
                if (type.isFunctionType) {
                    logger.warn("Found function type", this)
                    "TODO()"
                } else {
                    val typeDeclaration = type.declaration
                    if (typeDeclaration.isExposed || this.isExposed) "${typeDeclaration.accessFqn}($receiver)"
                    else null
                }
            }
        }
    }

    private val KSAnnotated.isExposed
        get() = isAnnotationPresent(LuajExpose::class) ||
                isAnnotationPresent(LuajExposeExternal::class)

    private val KSDeclaration.accessFqn
        get() = buildString {
            packageName.asString().takeIf(String::isNotEmpty)?.let {
                append(it)
                append('.')
            }
            append("access.")
            append(simpleName.asString())
            append("Access")
        }

    private fun PropertyLike.unsupportedTypeError(): Nothing = error(
        "Unsupported type for ${this.parentDeclaration}.${this.simpleName}: $type (${type.resolve()})",
        source
    )

    private fun error(message: String, at: KSNode): Nothing {
        logger.error(message, at)
        error(message)
    }

    private fun OutputStream.appendKotlin(@Language("kt") str: String) = this.write(str.toByteArray())
}