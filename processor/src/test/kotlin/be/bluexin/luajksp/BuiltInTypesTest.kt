package be.bluexin.luajksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCompilerApi::class)
class BuiltInTypesTest: LKSymbolProcessorTest() {

    @Test
    fun `test unit return type processing`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    class KClass(var cb: (Double) -> Unit)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        assertDoesNotThrow {
            result.classLoader.loadClass("access.KClassAccess")
        }

        class E: Exception()
        val data = result.instance("KClass", {_: Double -> throw E() })
        val access = result.instance("access.KClassAccess",  data)

        assertIs<LuaUserdata>(access)

        val cb = assertDoesNotThrow {
            access.get("cb")
        }

        assertIs<OneArgFunction>(cb)

        assertThrows<E> { cb.call(LuaValue.valueOf(42.0)) }
    }
}