package be.bluexin.luajksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCompilerApi::class)
class VisibilityTest: LKSymbolProcessorTest() {

    @Test
    fun `test private setter`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    class KClass {
                        var someValue: Int = 42
                            private set
                    }
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        assertDoesNotThrow {
            result.classLoader.loadClass("access.KClassAccess")
        }

        val data = result.instance("KClass")
        val access = result.instance("access.KClassAccess",  data)

        assertIs<LuaUserdata>(access)

        val someValue = assertDoesNotThrow {
            access.get("someValue")
        }
        assertEquals(42, someValue.checkint())
        assertThrows<LuaError> { access.set("someValue", LuaValue.valueOf(13)) }
    }
}