package be.bluexin.luajksp.generators

import be.bluexin.luajksp.ExposedData
import be.bluexin.luajksp.ExposedFunction
import be.bluexin.luajksp.ExposedPropertyLike
import be.bluexin.luajksp.annotations.LuajExpose
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import org.intellij.lang.annotations.Language
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
                """--- Generated with luaj-ksp
                |--- ${forDeclaration.docString.kdocToLDoc("    ")}
                |--- @class $targetClassName${
                    (if (forDeclaration is KSClassDeclaration) {
                        forDeclaration.superTypes
                            .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
                            .singleOrNull {
                                it.classKind == ClassKind.CLASS && it.isAnnotationPresent(LuajExpose::class)
                            }?.simpleName?.getShortName()?.takeIf(String::isNotBlank)?.let { ": $it" } ?: ""
                    } else "")
                }
                |$targetClassName = {${
                    properties.values.joinToString(separator = ",\n") { accessor ->
                        when (accessor) {
                            is ExposedPropertyLike -> """
                                |    --- ${accessor.docString.kdocToLDoc("    ")}
                                |    --- ${if (accessor.hasSetter) "mutable" else "immutable"}
                                |    --- @type ${luaType(accessor.type.resolve())}
                                |    ${accessor.simpleName} = nil"""

                            is ExposedFunction -> """
                                |    --- ${accessor.docString.kdocToLDoc("    ")}
                                |    --- immutable
                                |    --- @type fun(${funArgs(accessor.declaration)})${funReturnType(accessor.declaration)}
                                |    ${accessor.simpleName} = nil"""
                        }
                    }
                }
                |}
                """.trimMargin()
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

    private fun luaType(type: KSType) = when (val ts = type.declaration.simpleName.asString()) {
        "String" -> "string"
        "Int", "Double", "Long" -> "number"
        "Boolean" -> "boolean"
        "Unit" -> ""
        else -> {
            if (type.isFunctionType) "fun(${funArgs(type.arguments)})${funReturnType(type.arguments)}"
            else ts
        }
    }

    private fun String?.kdocToLDoc(indent: String = "") =
        this?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.replace(Regex("\n +"), "\n$indent--- ")
            ?: "No documentation provided"

    private fun OutputStream.appendLua(@Language("lua") str: String) = this.write(str.toByteArray())

}