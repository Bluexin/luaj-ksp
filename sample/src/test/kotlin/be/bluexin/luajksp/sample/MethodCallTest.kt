package be.bluexin.luajksp.sample

import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.sample.access.toLua
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MethodCallTest {

    @BeforeTest
    fun reset() = LuaJTest.resetContexts()

    @Test
    fun `method access works`() {

        val test = MethodHolder(42)

        LuaJTest.runTestScript(
            """
                --- @type MethodHolder
                local t = testing.testValue
                assert_equals(${test.truth}, t.truth, "truth")
                assert_equals(${test.getDoubleTruth()}, t.getDoubleTruth(), "getDoubleTruth()")
                t.truth = 71
                assert_equals(142, t.getDoubleTruth(), "getDoubleTruth()")
                assert_equals(9, t.setSomeStuff('test', 'hello'))
            """.trimIndent(),
            test.toLua()
        ).executionErrorAsFailure()

        assertEquals(71, test.truth)
    }

    @LuajExpose
    class MethodHolder(
        var truth: Int
    ) {
        fun getDoubleTruth() = truth * 2
        fun setSomeStuff(key: String, value: String) = key.length + value.length
    }
}