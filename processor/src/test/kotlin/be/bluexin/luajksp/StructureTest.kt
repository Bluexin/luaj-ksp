package be.bluexin.luajksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import kotlin.test.*

@OptIn(ExperimentalCompilerApi::class)
class StructureTest : LKSymbolProcessorTest() {

    @Test
    fun `non annotated nested class should not be included`() {
        val nestedClassName = "NestedClazs"

        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    class KClass(var text: String) {
                        class $nestedClassName(val foo: Int)
                    }
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        assertDoesNotThrow {
            result.classLoader.loadClass("access.KClassAccess")
        }
        assertThrows<ClassNotFoundException> {
            result.classLoader.loadClass("access.${nestedClassName}Access")
        }

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")
        assertFalse((nestedClassName + "Access") in result.messages)
    }

    @Test
    fun `annotated nested class should be included`() {
        val nestedClassName = "NestedClazs"

        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    class KClass(var text: String) {
                        @LuajExpose
                        class $nestedClassName(
                            var foo: Int,
                            val bar: Int
                        )
                    }
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")
        assertContains(result.messages, "Generating access.${nestedClassName}Access for $nestedClassName")

        assertDoesNotThrow {
            result.classLoader.loadClass("access.KClassAccess")
        }

        val instance = result.instance("KClass$$nestedClassName", 42, 420)
        val access = result.instance("access.${nestedClassName}Access", instance)

        assertIs<LuaUserdata>(access)
        assertEquals(LuaValue.valueOf(42), access.get("foo"))
        assertEquals(LuaValue.valueOf(420), access.get("bar"))
        assertThrows<LuaError> {
            access.get("text")
        }

        assertDoesNotThrow {
            access.set("foo", LuaValue.valueOf(31))
        }
        assertThrows<LuaError> {
            access.set("bar", LuaValue.valueOf(87))
        }
        assertThrows<LuaError> {
            access.set("text", LuaValue.valueOf("hello"))
        }

        assertEquals(LuaValue.valueOf(31), access.get("foo"))
        assertEquals(LuaValue.valueOf(420), access.get("bar"))
        assertThrows<LuaError> {
            access.get("text")
        }
    }
}