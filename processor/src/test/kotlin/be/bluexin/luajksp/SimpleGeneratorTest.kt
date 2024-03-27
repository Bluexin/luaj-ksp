package be.bluexin.luajksp

import com.tschuchort.compiletesting.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.luaj.vm2.LuaUserdata
import java.util.UUID
import kotlin.test.*

@OptIn(ExperimentalCompilerApi::class)
class SimpleGeneratorTest : LKSymbolProcessorTest() {

    @Test
    fun `test simple kotlin processing`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    class KClass(var text: String)
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

    @Test
    fun `test reference to typealias processing`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose
                    import be.bluexin.luajksp.annotations.LuajExposeExternal

                    @LuajExposeExternal("getMostSignificantBits", "getLeastSignificantBits")
                    typealias ExposedUUID = java.util.UUID

                    @LuajExpose
                    class KClass(var uuid: ExposedUUID)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        val uuid = UUID.randomUUID()
        val instance = result.instance("KClass", uuid)
        val access = result.instance("access.KClassAccess", instance)

        assertIs<LuaUserdata>(access)
        val foundUuidAccess = access.get("uuid")
        assertIs<LuaUserdata>(foundUuidAccess)
        assertEquals(uuid.toString(), foundUuidAccess.tojstring())
        assertEquals(uuid.leastSignificantBits.toDouble(), foundUuidAccess.get("leastSignificantBits").checkdouble())
        assertEquals(uuid.mostSignificantBits.toDouble(), foundUuidAccess.get("mostSignificantBits").checkdouble())
    }

    @Test
    fun `test simple java processing`() {
        val javaSource = SourceFile.java(
            "JClass.java", """
                    import be.bluexin.luajksp.annotations.LuajExpose;
                    
                    @LuajExpose
                    public class JClass {
                        private String text;
                        
                        public String getText() {
                            return text;
                        }
                        
                        public void setText(String text) {
                            this.text = text;
                        }       
                    }
                """
        )

        val result = compile(javaSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.JClassAccess for JClass")

        // Load compiled classes and inspect generated code through reflection
        val kClazz = result.classLoader.loadClass("JClass")
        val accessClazz = result.classLoader.loadClass("access.JClassAccess")
        assertTrue(accessClazz.declaredMethods.any { it.name == "get" })
        assertTrue(accessClazz.declaredMethods.any { it.name == "set" })
        assertTrue(LuaUserdata::class.java.isAssignableFrom(accessClazz))
    }
}
