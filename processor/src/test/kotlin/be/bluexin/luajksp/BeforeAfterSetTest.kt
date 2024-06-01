package be.bluexin.luajksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.mockk.slot
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import kotlin.test.*

@OptIn(ExperimentalCompilerApi::class)
class BeforeAfterSetTest: LKSymbolProcessorTest() {

    @Test
    fun `test BeforeSet`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.BeforeSet
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    class TestClass(var value: (String) -> Unit): BeforeSet {
                        override fun beforeSet() {
                            value("before")
                        }
                    }
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.TestClassAccess for TestClass")

        assertDoesNotThrow {
            result.classLoader.loadClass("access.TestClassAccess")
        }

        val slot = slot<String>()
        val data = result.instance("TestClass", {s: String -> slot.captured = s })
        val access = result.instance("access.TestClassAccess",  data)

        assertIs<LuaUserdata>(access)

        assertThrows<LuaError> {
            access.set("hello", "world")
        }

        assertTrue(slot.isCaptured)
        assertEquals("before", slot.captured)
    }

    @Test
    fun `test AfterSet`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.AfterSet
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    class TestClass(var value: (String) -> Unit): AfterSet {
                        override fun afterSet() {
                            value("after")
                        }
                    }
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.TestClassAccess for TestClass")

        assertDoesNotThrow {
            result.classLoader.loadClass("access.TestClassAccess")
        }

        val slot = slot<String>()
        val data = result.instance("TestClass", {s: String -> slot.captured = s })
        val access = result.instance("access.TestClassAccess",  data)

        assertIs<LuaUserdata>(access)

        assertThrows<LuaError> {
            access.set("hello", "world")
        }

        assertTrue(slot.isCaptured)
        assertEquals("after", slot.captured)
    }
}