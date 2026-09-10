package be.bluexin.luajksp.sample

import be.bluexin.luajksp.sample.lib.TestApiLib
import org.luaj.vm2.LuaValue
import kotlin.test.BeforeTest
import kotlin.test.Test

class LibTest {

    @BeforeTest
    fun reset() = LuaJTest.resetContexts()

    @Test
    fun `library installs its functions as a global`() {
        LuaJTest.runTestScript(
            """
                assert_equals("Hello Bob", api.greet("Bob"))
                assert_equals(42, api.double(21))
                assert_equals(7, api.apply(3, function(x) return x + 4 end))
                assert_equals(2, #api.bulk())
            """.trimIndent(),
            LuaValue.NIL,
            libs = listOf(TestApiLib(TestApi()))
        ).executionErrorAsFailure()
    }
}