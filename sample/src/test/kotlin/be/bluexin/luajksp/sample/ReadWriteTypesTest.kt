package be.bluexin.luajksp.sample

import be.bluexin.luajksp.annotations.LuajExclude
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.sample.access.toLua
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.TestInstance
import java.util.*
import kotlin.random.Random
import kotlin.reflect.KProperty0
import kotlin.test.*
import kotlin.test.Test

// TODO : Cover complex types
class ReadWriteTypesTest {

    @BeforeTest
    fun reset() = LuaJTest.resetContexts()

    @Test
    fun `reading all types is ok`() {
        val test = ReadWriteTypesHolder()

        @Language("lua")
        fun assertRead(expected: KProperty0<Any>) =
            "assert_equals(${expected().quoteIfNeeded}, _t.${expected.name}, \"${expected.name}\")"

        LuaJTest.runTestScript(
            test.fieldRefs.joinToString(separator = "\n", transform = ::assertRead),
            test.toLua()
        ).executionAsFailure()
    }

    @Test // Should we return nil instead to be more lua-like ?
    fun `reading non existing value is nok`() {
        val test = ReadOnlyTypesTest.ReadOnlyTypesHolder()

        val result = LuaJTest.runTestScript(
            "print(_t.foobar)",
            test.toLua()
        )
        assertIs<ScriptResult.Failure>(result)
        assertContains(result.errorMessage, "Cannot get foobar on ${ReadOnlyTypesTest.ReadOnlyTypesHolder::class.simpleName}")
    }

    @Test
    fun `writing any type is ok`() {
        val test = ReadWriteTypesHolder()
        val initial = test.copy()

        LuaJTest.runTestScript(
            """
                --- @type ReadWriteTypesHolder
                local t = testing.testValue
                _t.text = "newTest"
                _t.int = _t.int + 3
                _t.long = _t.long + 3
                _t.boolean = not _t.boolean
                _t.double = _t.double + 3
            """.trimIndent(),
            test.toLua()
        ).executionAsFailure()

        assertEquals("newTest", test.text)
        assertEquals(initial.int + 3, test.int)
        assertEquals(initial.long + 3, test.long)
        assertEquals(!initial.boolean, test.boolean)
        assertEquals(initial.double + 3, test.double)
    }

    @Test // Should we allow storing arbitrary data ?
    fun `writing non existing value is nok`() {
        val test = ReadOnlyTypesTest.ReadOnlyTypesHolder()

        val result = LuaJTest.runTestScript(
            "_t.foobar = 123",
            test.toLua()
        )
        assertIs<ScriptResult.Failure>(result)
        assertContains(result.errorMessage, "Cannot set foobar on ${ReadOnlyTypesTest.ReadOnlyTypesHolder::class.simpleName}")
    }

    @LuajExpose
    data class ReadWriteTypesHolder(
        var text: String = UUID.randomUUID().toString(),
        var int: Int = Random.nextInt(-1_000, 1_000),
        var long: Long = Random.nextLong(-10_000_000_000, 10_000_000_000),
        var boolean: Boolean = Random.nextBoolean(),
        var double: Double = Random.nextDouble()
    ) {

        /**
         * Dunno how to get reflection to go from KProperty to KProperty0
         */
        @LuajExclude
        val fieldRefs
            get() = arrayOf(
                ::text, ::int, ::long, ::boolean, ::double
            )
    }
}