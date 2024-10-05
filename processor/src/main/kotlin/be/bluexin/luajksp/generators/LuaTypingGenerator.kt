package be.bluexin.luajksp.generators

import be.bluexin.luajksp.*
import be.bluexin.luajksp.annotations.LuajExpose
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.ksp.toClassName
import java.io.OutputStream

@OptIn(KspExperimental::class)
internal class LuaTypingGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {

    fun generate(
        forDeclaration: KSDeclaration,
        properties: Map<String, ExposedData>
    ) {
        val targetClassName = forDeclaration.simpleName.asString()

        logger.info("Generating Lua typing for $targetClassName", forDeclaration)
        logger.logging("Properties : $properties")

        codeGenerator.createNewFileByPath(
            Dependencies(true, forDeclaration.containingFile!!),
            "lualib/$targetClassName",
            extensionName = "lua",
        ).use { file ->
            file.appendLua(
                """
                |--- Generated with luaj-ksp
                |--- ${forDeclaration.docString.kdocToLDoc()}
                |--- @class $targetClassName${
                    (if (forDeclaration is KSClassDeclaration) {
                        forDeclaration.superTypes
                            .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
                            .singleOrNull {
                                it.classKind == ClassKind.CLASS && it.isAnnotationPresent(LuajExpose::class)
                            }?.simpleName?.getShortName()?.takeIf(String::isNotBlank)?.let { ": $it" } ?: ""
                    } else "")
                }
                ${
                    properties.values.joinToString(separator = "\n") { accessor ->
                        when (accessor) {
                            is ExposedPropertyLike ->
                                """|--- ${accessor.docString.kdocToLDoc()}
                                |--- ${if (accessor.hasSetter) "mutable" else "immutable"}
                                |--- @field ${accessor.simpleName} ${luaType(accessor.type.resolve())}"""

                            is ExposedFunction ->
                                """|--- ${accessor.docString.kdocToLDoc()}
                                |--- immutable
                                |--- @field ${accessor.simpleName} fun(${funArgs(accessor.declaration)})${
                                    funReturnType(
                                        accessor.declaration
                                    )
                                }"""
                        }
                    }
                }""".trimMargin()
            )
        }
    }

    private fun funReturnType(decl: KSFunctionDeclaration): String = decl.returnType?.resolve()
        ?.let(::luaType)
        ?.takeIf(String::isNotEmpty)
        ?.let { ": $it" }
        ?: ""

    private fun funArgs(decl: KSFunctionDeclaration): String = decl.parameters.joinToString { param ->
        "${param.name?.let(KSName::getShortName) ?: "arg"}: ${luaType(param.type.resolve())}"
    }

    private fun funReturnType(decl: List<KSTypeArgument>): String = decl.last().type?.resolve()
        ?.let(::luaType)
        ?.takeIf(String::isNotEmpty)
        ?.let { ": $it" }
        ?: ""

    private fun funArgs(decl: List<KSTypeArgument>): String = decl.take(decl.size - 1).joinToString { arg ->
        "arg: ${luaType(arg.type!!.resolve())}"
    }

    private fun luaTypeSimpleMapping(typeDecl: KSDeclaration): String? {
        return when (typeDecl.simpleName.getShortName()) {
            "String" -> "string"
            "Int", "Double", "Long" -> "number"
            "Boolean" -> "boolean"
            "Unit" -> "void"
            else -> if (typeDecl is KSClassDeclaration) {
                when (typeDecl.toClassName()) {
                    LuaValueClassName -> "any"
                    LuaTableClassName -> "table"
                    LuaFunctionClassName -> "function"
                    else -> null
                }
            } else null
        }
    }

    private fun luaType(type: KSType): String = luaTypeSimpleMapping(type.declaration) ?: run {
        if (type.isFunctionType) "fun(${funArgs(type.arguments)})${funReturnType(type.arguments)}"
        else {
            val decl = type.declaration
            if (decl is KSClassDeclaration && decl.getAllSuperTypes()
                    .any { it.toClassName() == KotlinIterableName }
            ) {
                val bound = type.arguments.singleOrNull()?.type?.resolve()
                    ?: error("Expected a single argument type", type.declaration)
                "${luaType(bound)}[]"
            } else type.declaration.simpleName.getShortName()
        }
    }

    private fun String?.kdocToLDoc() =
        this?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.replace(Regex("\n *"), "\n--- ")
            ?: "No documentation provided"

    private fun OutputStream.appendLua(/*@Language("lua")*/ str: String) = this.write(str.toByteArray())

    private fun error(message: String, at: KSNode): Nothing {
        logger.error(message, at)
        error(message)
    }

}