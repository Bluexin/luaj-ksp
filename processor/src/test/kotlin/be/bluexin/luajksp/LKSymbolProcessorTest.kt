package be.bluexin.luajksp

import com.tschuchort.compiletesting.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.JvmTarget
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.reflect.full.primaryConstructor

@OptIn(ExperimentalCompilerApi::class)
abstract class LKSymbolProcessorTest {

    protected fun compile(vararg sources: SourceFile)  = KotlinCompilation().apply {
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
}