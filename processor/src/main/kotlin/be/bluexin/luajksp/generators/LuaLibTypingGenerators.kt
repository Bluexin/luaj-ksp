package be.bluexin.luajksp.generators

import be.bluexin.luajksp.annotations.LuajLib
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.OutputStream

/**
 * Emits a global-access declaration file for a [LuajLib] based library, pointing at the interface
 * generated for the library class itself by [TypeScriptTypingGenerator].
 */
internal class LuaLibTsTypingGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {
    fun generate(
        forDeclaration: KSClassDeclaration,
        lib: LuajLib,
        packagePaths: Map<String, String>
    ) {
        val targetClassName = forDeclaration.simpleName.asString()
        val dir = packagePaths.firstNotNullOfOrNull { (packageName, path) ->
            if (forDeclaration.packageName.asString().startsWith(packageName)) "$path/" else null
        } ?: ""

        logger.info("Generating global TypeScript typing for ${lib.name}", forDeclaration)

        codeGenerator.createNewFileByPath(
            dependencies = Dependencies(true, forDeclaration.containingFile!!),
            path = "typings/$dir${lib.name}",
            extensionName = "d.ts",
        ).use { file ->
            file.appendTs(
                """
                |// Generated with luaj-ksp
                |import { $targetClassName } from "./${targetClassName}";
                |
                |declare global {
                |    const ${lib.name}: $targetClassName;
                |}
                |export {};
                |""".trimMargin()
            )
        }
    }
}

/**
 * Emits an EmmyLua-ish annotation declaring the global table a [LuajLib] based library installs.
 */
internal class LuaLibLuaTypingGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {
    fun generate(
        forDeclaration: KSClassDeclaration,
        lib: LuajLib,
        packagePaths: Map<String, String>
    ) {
        val targetClassName = forDeclaration.simpleName.asString()
        val dir = packagePaths.firstNotNullOfOrNull { (packageName, path) ->
            if (forDeclaration.packageName.asString().startsWith(packageName)) "$path/" else null
        } ?: ""

        logger.info("Generating global Lua typing for ${lib.name}", forDeclaration)

        codeGenerator.createNewFileByPath(
            dependencies = Dependencies(true, forDeclaration.containingFile!!),
            path = "lualib/$dir${lib.name}",
            extensionName = "lua",
        ).use { file ->
            file.appendLua(
                """
                |--- Generated with luaj-ksp
                |---
                |--- @type $targetClassName
                |${lib.name} = {}
                |
                """.trimMargin()
            )
        }
    }
}

private fun OutputStream.appendTs(str: String) = this.write(str.toByteArray())
private fun OutputStream.appendLua(str: String) = this.write(str.toByteArray())