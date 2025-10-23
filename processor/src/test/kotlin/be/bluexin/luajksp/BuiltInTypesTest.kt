package be.bluexin.luajksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.luaj.vm2.*
import org.luaj.vm2.LuaValue.tableOf
import org.luaj.vm2.LuaValue.valueOf
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.VarArgFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.test.*

@OptIn(ExperimentalCompilerApi::class)
class BuiltInTypesTest : LKSymbolProcessorTest() {

    @Test
    fun `test unit return type KFunction processing`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    class KClass(var cb: (value: Double) -> Unit)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        assertDoesNotThrow {
            result.classLoader.loadClass("access.KClassAccess")
        }

        class E : Exception()

        val data = result.instance("KClass", { _: Double -> throw E() })
        val access = result.instance("access.KClassAccess", data)

        assertIs<LuaUserdata>(access)

        val cb = assertDoesNotThrow {
            access.get("cb")
        }

        assertIs<OneArgFunction>(cb)

        assertThrows<E> { cb.call(valueOf(42.0)) }

        val typings = result.typings("KClass")

        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "cb: (value: number) => void")

        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field cb fun(value: number): void")
    }

    @Test
    fun `test high arity KFunction processing`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    class KClass(var cb: (one: Int, two: Int, Int, four: Int) -> Int)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        assertDoesNotThrow {
            result.classLoader.loadClass("access.KClassAccess")
        }

        val data = result.instance("KClass", { _: Int, _: Int, _: Int, _: Int -> 0 })
        val access = result.instance("access.KClassAccess", data)

        assertIs<LuaUserdata>(access)
        val cb = assertDoesNotThrow {
            access.get("cb")
        }

        assertIs<VarArgFunction>(cb)

        val args = slot<Varargs>()
        val callback = mockk<LuaFunction> {
            every { checkfunction() } returns this
            every { this@mockk.invoke(capture(args)) } returns valueOf(42)
        }

        assertDoesNotThrow {
            access.set("cb", callback)
        }

        val cbProp = data::class.declaredMemberProperties.singleOrNull()
        assertNotNull(cbProp)
        assertIs<KProperty1<Any, *>>(cbProp) // Just to make compiler happy, this has no value
        val wrappedCb = cbProp(data)
        assertIs<(Int, Int, Int, Int) -> Int>(wrappedCb) // will only check for Function4
        val res = wrappedCb(1, 2, 3, 4)
        assertIs<Int>(res)
        assertEquals(42, res)

        assertTrue(args.isCaptured)
        val captured = args.captured
        assertEquals(4, captured.narg())
        assertEquals(1, captured.arg1().checkint())
        assertEquals(2, captured.arg(2).checkint())
        assertEquals(3, captured.arg(3).checkint())
        assertEquals(4, captured.arg(4).checkint())

        assertEquals("KClass", access.typename())

        val typings = result.typings("KClass")

        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "cb: (one: number, two: number, arg2: number, four: number) => number")

        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field cb fun(one: number, two: number, arg2: number, four: number): number")
    }

    @Test
    fun `test receiver KFunction processing`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    data class KClass(var cb: KClass.(value: Int, Double) -> Boolean)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        assertDoesNotThrow {
            result.classLoader.loadClass("access.KClassAccess")
        }

        val data = result.instance("KClass", { _: Any /* yes, hax */, _: Int, _: Double -> false })
        val access = result.instance("access.KClassAccess", data)

        assertIs<LuaUserdata>(access)
        val cb = assertDoesNotThrow {
            access.get("cb")
        }

        assertIs<ThreeArgFunction>(cb)

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
        assertIs<Any.(Int, Double) -> Boolean>(wrappedCb) // will only check for Function2
        val res = wrappedCb(data, 42, 13.37)
        assertIs<Boolean>(res)
        assertTrue(res)

        assertTrue(args.isCaptured)
        val captured = args.captured
        assertEquals(3, captured.narg())
        assertEquals(access, captured.arg1())
        assertEquals(42, captured.arg(2).checkint())
        assertEquals(13.37, captured.arg(3).checkdouble())

        val data2 = result.instance("KClass", { _: Any /* yes, hax */, _: Int, _: Double -> false })

        args.clear()
        val res2 = wrappedCb(data2, 71, 84.3)
        assertIs<Boolean>(res2)
        assertTrue(res2)

        assertTrue(args.isCaptured)
        val captured2 = args.captured
        assertEquals(3, captured2.narg())
        assertNotEquals(access, captured2.arg1())
        assertEquals(71, captured2.arg(2).checkint())
        assertEquals(84.3, captured2.arg(3).checkdouble())

        assertEquals("KClass", access.typename())

        val typings = result.typings("KClass")

        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "cb: (this: KClass, value: number, arg1: number) => boolean")

        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field cb fun(self: KClass, value: number, arg1: number): boolean")
    }

    @Test
    fun `test iterable processing`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose

                    @LuajExpose
                    class KClass(val list: List<String>)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        assertDoesNotThrow {
            result.classLoader.loadClass("access.KClassAccess")
        }

        val input = listOf("hello", "world")
        val data = result.instance("KClass", input)
        val access = result.instance("access.KClassAccess", data)

        assertIs<LuaUserdata>(access)

        val list = assertDoesNotThrow {
            access.get("list")
        }

        assertTrue(list.istable())

        input.forEachIndexed { index, s ->
            val v = list[index + 1]
            assertTrue(v.isstring(), "Unexpected type at index $index: ${v.typename()}")
            assertEquals(s, v.checkjstring(), "Unexpected value at index $index")
        }

        val typings = result.typings("KClass")

        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "list: string[]")

        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field list string[]")
    }

    @Test
    fun `test iterable processing for non final class that does not implement LKExposed logs warning`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose
                    
                    @LuajExpose
                    open class NonFinal

                    @LuajExpose
                    class KClass(val list: List<NonFinal>)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(
            result.messages,
            "Exposing open type that does not implement be.bluexin.luajksp.annotations.LKExposed, this will not follow inheritance !"
        )

        val typings = result.typings("KClass")

        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "list: NonFinal[]")

        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field list NonFinal[]")
    }

    @Test
    fun `test iterable processing for non final class that implements LKExposed does not log warning`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose
                    import be.bluexin.luajksp.annotations.LKExposed
                    import org.luaj.vm2.LuaValue
                    
                    @LuajExpose(LuajExpose.IncludeType.OPT_IN)
                    open class NonFinal: LKExposed {
                        override fun toLua(): LuaValue = TODO()
                    }

                    @LuajExpose
                    class KClass(val list: List<NonFinal>)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertFalse("Exposing open type" in result.messages, "Expected to not find a warning in the logs")

        val typings = result.typings("KClass")
        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "list: NonFinal[]")
        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field list NonFinal[]")
    }

    @Test
    fun `test iterable processing as LKExposed does not log warning`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose
                    import be.bluexin.luajksp.annotations.LKExposed
                    import org.luaj.vm2.LuaValue

                    @LuajExpose
                    class KClass(val list: List<LKExposed>)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertFalse("Exposing open type" in result.messages, "Expected to not find a warning in the logs")

        val typings = result.typings("KClass")
        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "list: LKExposed[]")
        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field list LKExposed[]")
    }

    @Test
    fun `test LKExposed is handled`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose
                    import be.bluexin.luajksp.annotations.LKExposed
                    import org.luaj.vm2.LuaValue

                    @LuajExpose
                    class KClass(val exposed: LKExposed)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertFalse("Exposing open type" in result.messages, "Expected to not find a warning in the logs")

        val typings = result.typings("KClass")
        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "exposed: LKExposed")
        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field exposed LKExposed")
    } // TODO : support default args ? support lib function ?

    @Test
    fun `test LKExposed implementation is handled`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose
                    import be.bluexin.luajksp.annotations.LKExposed
                    import org.luaj.vm2.LuaValue

                    @LuajExpose(LuajExpose.IncludeType.OPT_IN)
                    open class NonFinal: LKExposed {
                        override fun toLua(): LuaValue = TODO()
                    }

                    @LuajExpose
                    class KClass(val exposed: NonFinal)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertFalse("Exposing open type" in result.messages, "Expected to not find a warning in the logs")

        val typings = result.typings("KClass")
        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "exposed: NonFinal")
        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field exposed NonFinal")
    }

    @Test
    fun `test LuaValue is handled`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose
                    import be.bluexin.luajksp.annotations.LKExposed
                    import org.luaj.vm2.LuaValue

                    @LuajExpose
                    class KClass(var value: LuaValue)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        val value = valueOf(42)
        val instance = result.instance("KClass", value)
        val access = result.instance("access.KClassAccess", instance)

        assertIs<LuaUserdata>(access)
        val foundValue = access.get("value")
        assertIs<LuaValue>(foundValue)
        assertTrue(foundValue.isnumber())
        assertEquals(42, foundValue.checkint())
        assertSame(value, foundValue)

        val newValue = valueOf("hello world")
        access.set("value", newValue)

        val newFoundValue = access.get("value")
        assertIs<LuaValue>(newFoundValue)
        assertTrue(newFoundValue.isstring())
        assertEquals("hello world", newFoundValue.checkjstring())
        assertSame(newValue, newFoundValue)

        val typings = result.typings("KClass")
        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "value: any")
        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field value any")
    }

    @Test
    fun `test LuaTable is handled`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose
                    import be.bluexin.luajksp.annotations.LKExposed
                    import org.luaj.vm2.LuaTable

                    @LuajExpose
                    class KClass(var value: LuaTable)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        val value = tableOf(
            arrayOf(
                valueOf("hello"), valueOf("world"),
                valueOf("truth"), valueOf(42)
            )
        )
        val instance = result.instance("KClass", value)
        val access = result.instance("access.KClassAccess", instance)

        assertIs<LuaUserdata>(access)
        val foundValue = access.get("value")
        assertIs<LuaTable>(foundValue)
        assertEquals("world", foundValue["hello"].checkjstring())
        assertEquals(42, foundValue["truth"].checkint())
        assertSame(value, foundValue)

        val newValue = tableOf(arrayOf(valueOf("sweet"), valueOf("dreams")))
        access.set("value", newValue)

        val newFoundValue = access.get("value")
        assertIs<LuaTable>(newFoundValue)
        assertEquals("dreams", newFoundValue["sweet"].checkjstring())
        assertSame(newValue, newFoundValue)

        val typings = result.typings("KClass")
        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "value: Record<string, any>")
        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field value table")
    }

    @Test
    fun `test LuaFunction is handled`() {
        val kotlinSource = SourceFile.kotlin(
            "KClass.kt", """
                    import be.bluexin.luajksp.annotations.LuajExpose
                    import be.bluexin.luajksp.annotations.LKExposed
                    import org.luaj.vm2.LuaFunction

                    @LuajExpose
                    class KClass(var value: LuaFunction)
                """
        )

        val result = compile(kotlinSource)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Diagnostics
        assertContains(result.messages, "Generating access.KClassAccess for KClass")

        val value = object : LuaFunction() {}
        val instance = result.instance("KClass", value)
        val access = result.instance("access.KClassAccess", instance)

        assertIs<LuaUserdata>(access)
        val foundValue = access.get("value")
        assertIs<LuaFunction>(foundValue)
        assertSame(value, foundValue)

        val newValue = object : LuaFunction() {}
        access.set("value", newValue)

        val newFoundValue = access.get("value")
        assertIs<LuaFunction>(newFoundValue)
        assertSame(newValue, newFoundValue)

        val typings = result.typings("KClass")
        val tsTyping = typings[GeneratedTypings.TYPESCRIPT]
        assertNotNull(tsTyping)
        assertContains(tsTyping, "value: (...args: any[]) => any")
        val luaTyping = typings[GeneratedTypings.LUA]
        assertNotNull(luaTyping)
        assertContains(luaTyping, "--- @field value function")
    }
}