package be.bluexin.luajksp.sample

import be.bluexin.luajksp.annotations.LuajExclude
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import be.bluexin.luajksp.sample.access.toLua
import org.intellij.lang.annotations.Language
import java.util.*
import kotlin.random.Random
import kotlin.reflect.KProperty0
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs

// TODO : Cover complex types
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
        ).executionAsFailure()
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
        ).executionAsFailure()
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
        ).executionAsFailure()
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

        arrayOf("something", "getSomething").forEach {
            val result = LuaJTest.runTestScript(
                "print(_t.$it)",
                test.toLua()
            )
            assertIs<ScriptResult.Failure>(result)
            assertContains(result.errorMessage, "Cannot get $it on ${ReadOnlyTypesHolder::class.simpleName}")
        }
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

    @LuajExpose
    data class ReadOnlyTypesHolder(
        val text: String = UUID.randomUUID().toString(),
        val int: Int = Random.nextInt(-1_000, 1_000),
        val long: Long = Random.nextLong(-10_000_000_000, 10_000_000_000),
        val boolean: Boolean = Random.nextBoolean(),
        val double: Double = Random.nextDouble(),
        val nullableText: String? = null,
    ) {

        /**
         * Don't know how to get reflection to go from KProperty to KProperty0
         */
        @LuajExclude
        val fieldRefs
            get() = arrayOf(
                ::text, ::int, ::long, ::boolean, ::double, ::nullableText
            )

        fun getSomething(): String = ""
    }
}

@LuajExposeExternal("getMostSignificantBits", "getLeastSignificantBits")
typealias ExposedUUID = UUID
