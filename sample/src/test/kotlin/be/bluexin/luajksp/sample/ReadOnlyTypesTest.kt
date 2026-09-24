package be.bluexin.luajksp.sample

import be.bluexin.luajksp.annotations.LuajExclude
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import be.bluexin.luajksp.sample.access.toLua
import org.intellij.lang.annotations.Language
import java.util.*
import kotlin.random.Random
import kotlin.reflect.KProperty0
import kotlin.test.*
import kotlin.test.Test

class ReadOnlyTypesTest {

    @BeforeTest
    fun reset() = LuaJTest.resetContexts()

    @Test
    fun `reading all types is ok`() {
        val test = ReadOnlyTypesHolder()

        @Language("lua")
        fun assertRead(expected: KProperty0<Any?>) =
            "assert_equals(${expected().quoteIfNeeded}, _t.${expected.name}, \"${expected.name}\")"

        LuaJTest.runTestScript(
            test.fieldRefs.joinToString(separator = "\n", transform = ::assertRead),
            test.toLua()
        ).executionErrorAsFailure()

        assertEquals(ReadOnlyTypesHolder::class.simpleName, test.toLua().typename())
    }

    @Test
    fun `reading on annotated typealias is ok using property access syntax`() {
        val test: ExposedUUID = UUID.randomUUID()

        LuaJTest.runTestScript(
            """
                --- @type ExposedUUID
                local t = testing.testValue
                assert_equals(${test.mostSignificantBits}, t.mostSignificantBits, "mostSignificantBits")
                assert_equals(${test.leastSignificantBits}, t.leastSignificantBits, "leastSignificantBits")
                assert_equals(${test.toString().quoteIfNeeded}, tostring(t), "tostring")
            """.trimIndent(),
            test.toLua()
        ).executionErrorAsFailure()
    }

    @Test
    fun `reading on field of type annotated typealias is ok using property access syntax`() {
        val test = ReadOnlyTypesHolder()

        LuaJTest.runTestScript(
            """
                --- @type ReadOnlyTypesHolder
                local t = testing.testValue
                assert_equals(${test.uuid.mostSignificantBits}, t.uuid.mostSignificantBits, "mostSignificantBits")
                assert_equals(${test.uuid.leastSignificantBits}, t.uuid.leastSignificantBits, "leastSignificantBits")
                assert_equals(${test.uuid.toString().quoteIfNeeded}, tostring(t.uuid), "tostring")
            """.trimIndent(),
            test.toLua()
        ).executionErrorAsFailure()
    }

    @Test
    fun `reading on annotated java class is ok using property access syntax`() {
        val test = JavaPropertyLikeHolder().apply {
            text = UUID.randomUUID().toString()
        }

        LuaJTest.runTestScript(
            """
                --- @type JavaPropertyLikeHolder
                local t = testing.testValue
                assert_equals(${test.text.quoteIfNeeded}, t.text, "text")
                assert_equals(${test.toString().quoteIfNeeded}, tostring(t), "tostring")
            """.trimIndent(),
            test.toLua()
        ).executionErrorAsFailure()
    }

    @Test
    fun `reading on list type is ok`() {
        val test = ReadOnlyTypesHolder()

        LuaJTest.runTestScript(
            """
                |--- @type ReadOnlyTypesHolder
                |local t = testing.testValue
                |assert_equals(${test.list.size}, #t.list, 'list.size')
                ${
                    test.list.mapIndexed { index, s ->
                        "|assert_equals('$s', t.list[${index + 1}], 'list[${index + 1}]')"
                    }.joinToString(separator = "\n")
                }
            |""".trimMargin(),
            test.toLua()
        ).executionErrorAsFailure()
    }

    @Test // Should we return nil instead to be more lua-like ?
    fun `reading non existing value is nok`() {
        val test = ReadOnlyTypesHolder()

        val result = LuaJTest.runTestScript(
            "print(_t.foobar)",
            test.toLua()
        )
        assertIs<ScriptResult.Failure>(result)
        assertContains(result.errorMessage, "Cannot get foobar on ${ReadOnlyTypesHolder::class.simpleName}")
    }

    @Test
    fun `property access syntax not available for Kotlin property-like`() {
        val test = ReadOnlyTypesHolder()


        val result = LuaJTest.runTestScript(
            "print(_t.something)",
            test.toLua()
        )
        assertIs<ScriptResult.Failure>(result)
        assertContains(result.errorMessage, "Cannot get something on ${ReadOnlyTypesHolder::class.simpleName}")

        LuaJTest.runTestScript(
            "assert_equals('something', _t.getSomething(), 'getSomething')",
            test.toLua()
        ).executionErrorAsFailure()
    }

    @Test
    fun `writing any type is nok`() {
        val test = ReadOnlyTypesHolder()

        test.fieldRefs.forEach {
            val result = LuaJTest.runTestScript(
                "_t.${it.name} = 123",
                test.toLua()
            )
            assertIs<ScriptResult.Failure>(result)
            assertContains(result.errorMessage, "Cannot set ${it.name} on ${ReadOnlyTypesHolder::class.simpleName}")
        }
    }

    @Test
    fun `reading on map type is ok`() {
        val test = ReadOnlyTypesHolder()

        LuaJTest.runTestScript(
            """
                |--- @type ReadOnlyTypesHolder
                |local t = testing.testValue
                |assert_equals(${test.map.size}, #t.map, 'map.size')
                ${
                test.map.entries.joinToString(separator = "\n") { (k, v) ->
                    "|assert_equals($v, t.map['$k'], \"map['$k']\")"
                }
            }
            |assert_equals(nil, t.map['missing'], "map['missing']")
            |""".trimMargin(),
            test.toLua()
        ).executionErrorAsFailure()
    }

    @Test
    fun `mutating map type from a script is ok and observed by Kotlin`() {
        val test = ReadOnlyTypesHolder()

        LuaJTest.runTestScript(
            """
                --- @type ReadOnlyTypesHolder
                local t = testing.testValue
                t.map['three'] = 3
                t.map['one'] = nil
                assert_equals(nil, t.map['one'], "map['one'] after delete")
            """.trimIndent(),
            test.toLua()
        ).executionErrorAsFailure()

        assertEquals(mapOf("two" to 2, "three" to 3), test.map)
    }

    @Test
    fun `iterating map type with pairs is ok`() {
        val test = ReadOnlyTypesHolder()

        LuaJTest.runTestScript(
            """
                |--- @type ReadOnlyTypesHolder
                |local t = testing.testValue
                |local seen = {}
                |local count = 0
                |for k, v in pairs(t.map) do
                |    seen[k] = v
                |    count = count + 1
                |end
                |assert_equals(${test.map.size}, count, 'iterated count')
                ${
                test.map.entries.joinToString(separator = "\n") { (k, v) ->
                    "|assert_equals($v, seen['$k'], \"seen['$k']\")"
                }
            }
            |""".trimMargin(),
            test.toLua()
        ).executionErrorAsFailure()
    }

    @Test
    fun `assigning a table to map type replaces its contents`() {
        val test = ReadOnlyTypesHolder()

        LuaJTest.runTestScript(
            """
                --- @type ReadOnlyTypesHolder
                local t = testing.testValue
                t.map = { foo = 3, bar = 4 }
            """.trimIndent(),
            test.toLua()
        ).executionErrorAsFailure()

        assertEquals(mapOf("foo" to 3, "bar" to 4), test.map)
    }

    @LuajExpose
    data class ReadOnlyTypesHolder(
        val text: String = UUID.randomUUID().toString(),
        val int: Int = Random.nextInt(-1_000, 1_000),
        val long: Long = Random.nextLong(-10_000_000_000, 10_000_000_000),
        val boolean: Boolean = Random.nextBoolean(),
        val double: Double = Random.nextDouble(),
        val nullableText: String? = null,
        val uuid: ExposedUUID = UUID.randomUUID(),
        val list: List<String> = listOf("hello", "world"),
        val map: MutableMap<String, Int> = mutableMapOf("one" to 1, "two" to 2)
    ) {

        /**
         * Don't know how to get reflection to go from KProperty to KProperty0
         */
        @LuajExclude
        val fieldRefs
            get() = arrayOf(
                ::text, ::int, ::long, ::boolean, ::double, ::nullableText
            )

        fun getSomething(): String = "something"
    }
}

@LuajExposeExternal("getMostSignificantBits", "getLeastSignificantBits")
typealias ExposedUUID = UUID
