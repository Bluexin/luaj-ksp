package be.bluexin.luajksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.JvmTarget

@OptIn(ExperimentalCompilerApi::class)
abstract class LuajSymbolProcessorTest {

    protected fun compile(vararg sources: SourceFile)  = KotlinCompilation().apply {
        this.sources = sources.toList()
        jvmTarget = JvmTarget.JVM_17.description
        symbolProcessorProviders = listOf(LuajSymbolProcessor.Provider())
        inheritClassPath = true
        messageOutputStream = System.out // see diagnostics in real time
        kspWithCompilation = true
    }.compile()
}