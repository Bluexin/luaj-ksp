package be.bluexin.luajksp.sample

import org.intellij.lang.annotations.Language
import org.luaj.vm2.LuaValue
import java.io.File
import kotlin.test.fail

private class T

val luaAssertionSupport by lazy {
    T::class.java.classLoader.getResource("assertions.lua")!!.toURI().let(::File).readText()
}

val Any.quoteIfNeeded get() = if (this is String) "\"$this\"" else toString()

fun LuaJTest.runTestScript(
    @Language("lua") snippet: String,
    testValue: LuaValue
): ScriptResult {
    val fullScript = """
        |$luaAssertionSupport
        |$snippet
    """.trimMargin()
    return runScript(
        "test", fullScript
    ) {
        mapOf("testValue" to testValue)
    }
}

fun ScriptResult.executionAsFailure() {
    if (this is ScriptResult.Failure) {
        var index = 1 // Lua index starts at 1
        fail("Script crashed : ${this.errorMessage}\nScript being evaluated: \n${
            snippet.split("\n").joinToString(separator = "\n") {
                "${index++} - $it"
            }
        }")
    }
}
