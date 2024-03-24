package be.bluexin.luajksp.sample

import be.bluexin.luajksp.annotations.LuajExclude
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.sample.access.toLua
import org.intellij.lang.annotations.Language
import java.util.*
import kotlin.random.Random
import kotlin.reflect.KProperty0
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

// TODO : Cover complex types
class ReadWriteTypesTest {

    @BeforeTest
    fun reset() = LuaJTest.resetContexts()

    @Test
    fun `reading all types is ok`() {
        val test = ReadWriteTypesHolder()

        @Language("lua")
        fun assertRead(expected: KProperty0<Any?>) =
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
        val test = ReadWriteTypesHolder(nullableText = UUID.randomUUID().toString())
        val initial = test.copy()

        LuaJTest.runTestScript(
            """
                --- @type ReadWriteTypesHolder
                local t = testing.testValue
                t.text = "newTest"
                t.int = _t.int + 3
                t.long = _t.long + 3
                t.boolean = not _t.boolean
                t.double = _t.double + 3
                t.nullableText = nil
            """.trimIndent(),
            test.toLua()
        ).executionAsFailure()

        assertEquals("newTest", test.text)
        assertEquals(initial.int + 3, test.int)
        assertEquals(initial.long + 3, test.long)
        assertEquals(!initial.boolean, test.boolean)
        assertEquals(initial.double + 3, test.double)
        assertEquals(null, test.nullableText)
    }

    @Test // Should we allow storing arbitrary data ?
    fun `writing non existing value is nok`() {
        val test = ReadWriteTypesHolder()

        val result = LuaJTest.runTestScript(
            "_t.foobar = 123",
            test.toLua()
        )
        assertIs<ScriptResult.Failure>(result)
        assertContains(result.errorMessage, "Cannot set foobar on ${ReadWriteTypesHolder::class.simpleName}")
    }

    @Test
    fun `writing nil value in non-nullable field is nok`() {
        val test = ReadWriteTypesHolder()

        val result = LuaJTest.runTestScript(
            "_t.text = nil",
            test.toLua()
        )
        assertIs<ScriptResult.Failure>(result)
        assertContains(result.errorMessage, "bad argument: value expected, got nil")
    }

    @LuajExpose
    data class ReadWriteTypesHolder(
        var text: String = UUID.randomUUID().toString(),
        var int: Int = Random.nextInt(-1_000, 1_000),
        var long: Long = Random.nextLong(-10_000_000_000, 10_000_000_000),
        var boolean: Boolean = Random.nextBoolean(),
        var double: Double = Random.nextDouble(),
        var nullableText: String? = null,
    ) {

        /**
         * Dunno how to get reflection to go from KProperty to KProperty0
         */
        @LuajExclude
        val fieldRefs
            get() = arrayOf(
                ::text, ::int, ::long, ::boolean, ::double, ::nullableText
            )
    }
}