package be.bluexin.luajksp

import com.tschuchort.compiletesting.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.JvmTarget
import org.luaj.vm2.LuaUserdata
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCompilerApi::class)
class SimpleGeneratorTest : LuajSymbolProcessorTest() {

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
