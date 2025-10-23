package be.bluexin.luajksp

import com.tschuchort.compiletesting.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.JvmTarget
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.File
import kotlin.reflect.full.primaryConstructor

@OptIn(ExperimentalCompilerApi::class)
abstract class LKSymbolProcessorTest {

    protected fun compile(vararg sources: SourceFile) = KotlinCompilation().apply {
        this.sources = sources.toList()
        jvmTarget = JvmTarget.JVM_17.description
        symbolProcessorProviders = listOf(LKSymbolProcessor.Provider())
        inheritClassPath = true
        messageOutputStream = System.out // see diagnostics in real time
        kspWithCompilation = true
    }.compile()

    protected fun JvmCompilationResult.instance(clazz: String, vararg args: Any?): Any = assertDoesNotThrow {
        classLoader.loadClass(clazz).kotlin.primaryConstructor!!.call(*args)
    }

    protected fun JvmCompilationResult.typings(clazz: String): Map<GeneratedTypings, String?> {
        val typingsDir = File(outputDirectory.parentFile, "ksp/sources/resources")

        return GeneratedTypings.entries.associateWith {
            val typingsFile = File(typingsDir, it.fileNameMapping(clazz))

            if (typingsFile.isFile) typingsFile.readText() else null
        }
    }

    protected enum class GeneratedTypings(val fileNameMapping: (clazz: String) -> String) {
        TYPESCRIPT({ "typings/$it.d.ts" }),
        LUA({ "lualib/$it.lua" }),
    }
}