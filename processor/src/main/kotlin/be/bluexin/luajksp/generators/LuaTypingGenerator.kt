package be.bluexin.luajksp.generators

import be.bluexin.luajksp.PropertyLike
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import org.intellij.lang.annotations.Language
import java.io.OutputStream

internal class LuaTypingGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {

    fun generate(
        forDeclaration: KSDeclaration,
        properties: Map<String, PropertyLike>
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
                |--- @class $targetClassName
                |$targetClassName = {${
                    properties.values.joinToString(separator = ",\n") {
                        """
                        |    --- ${it.docString.kdocToLDoc("    ")}
                        |    --- ${if (it.hasSetter) "mutable" else "immutable"}
                        |    --- @type ${luaType(it.type)}
                        |    ${it.simpleName} = nil"""
                    }
                }
                |}
                """.trimMargin()
            )
        }
    }


    private fun luaType(type: KSTypeReference) = when (val ts = type.toString()) {
        "String" -> "string"
        "Int", "Double", "Long" -> "number"
        "Boolean" -> "boolean"
        else -> ts
    }

    private fun String?.kdocToLDoc(indent: String = "") =
        this?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.replace("\n", "\n$indent---")
            ?: "No documentation provided"

    private fun OutputStream.appendLua(@Language("lua") str: String) = this.write(str.toByteArray())

}