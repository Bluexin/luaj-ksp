package be.bluexin.luajksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.test.*

@OptIn(ExperimentalCompilerApi::class)
class BuiltInTypesTest: LKSymbolProcessorTest() {

    @Test
    fun `test unit return type KFunction processing`() {
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

    @Test
    fun `test receiver KFunction processing`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    data class KClass(var cb: KClass.(Int) -> Boolean)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        assertDoesNotThrow {
            result.classLoader.loadClass("access.KClassAccess")
        }

        val data = result.instance("KClass", { _: Any /* yes, hax */, _: Int -> false })
        val access = result.instance("access.KClassAccess",  data)

        assertIs<LuaUserdata>(access)
        val cb = assertDoesNotThrow {
            access.get("cb")
        }

        assertIs<TwoArgFunction>(cb)

        val args = slot<Varargs>()
        val callback = mockk<LuaFunction> {
            every { checkfunction() } returns this
            every { this@mockk.invoke(capture(args)) } returns LuaValue.TRUE
        }

        assertDoesNotThrow {
            access.set("cb", callback)
        }

        val cbProp = data::class.declaredMemberProperties.singleOrNull()
        assertNotNull(cbProp)
        assertIs<KProperty1<Any, *>>(cbProp) // Just to make compiler happy, this has no value
        val wrappedCb = cbProp(data)
        assertIs<Any.(Int) -> Boolean>(wrappedCb) // will only check for KFunction2
        val res = wrappedCb(data, 42)
        assertIs<Boolean>(res)
        assertTrue(res)

        assertTrue(args.isCaptured)
        val captured = args.captured
        assertEquals(2, captured.narg())
        assertEquals(access, captured.arg1())
        assertEquals(42, captured.arg(2).checkint())

        val data2 = result.instance("KClass", { _: Any /* yes, hax */, _: Int -> false })

        args.clear()
        val res2 = wrappedCb(data2, 71)
        assertIs<Boolean>(res2)
        assertTrue(res2)

        assertTrue(args.isCaptured)
        val captured2 = args.captured
        assertEquals(2, captured2.narg())
        assertNotEquals(access, captured2.arg1())
        assertEquals(71, captured2.arg(2).checkint())
    }
}