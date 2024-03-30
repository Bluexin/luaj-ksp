package be.bluexin.luajksp

import com.tschuchort.compiletesting.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Disabled
import org.luaj.vm2.LuaUserdata
import java.util.UUID
import kotlin.test.*

@OptIn(ExperimentalCompilerApi::class)
class MapperTest : LKSymbolProcessorTest() {

    @Test
    @Disabled
    fun `test simple kotlin processing`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose
                    import be.bluexin.luajksp.annotations.LuajMapped
                    import be.bluexin.luajksp.annotations.LKMapper

                    @LuajExpose
                    class KClass(var text: String)

                    @LuajMapped(mapper = Mapper::class)
                    class ToBeMapped

                    class Mapper: LKMapper()
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        // Load compiled classes and inspect generated code through reflection
        val kClazz = result.classLoader.loadClass("KClass")
        val accessClazz = result.classLoader.loadClass("access.KClassAccess")
        assertTrue(accessClazz.declaredMethods.any { it.name == "get" })
        assertTrue(accessClazz.declaredMethods.any { it.name == "set" })
        assertTrue(LuaUserdata::class.java.isAssignableFrom(accessClazz))
    }
}
