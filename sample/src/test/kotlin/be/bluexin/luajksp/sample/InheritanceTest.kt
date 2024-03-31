package be.bluexin.luajksp.sample

import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.sample.access.toLua
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InheritanceTest {

    @BeforeTest
    fun reset() = LuaJTest.resetContexts()

    @Test
    fun `inheritance access works`() {

        val test = ChildClass(42)

        LuaJTest.runTestScript(
            """
                --- @type ChildClass
                local t = testing.testValue
                assert_equals(${test.age.quoteIfNeeded}, t.age, "age")
                assert_equals(${test.name.quoteIfNeeded}, t.name, "name")
                t.age = 71
                t.name = "Hello from Lua"
            """.trimIndent(),
            test.toLua()
        ).executionAsFailure()

        assertEquals(71, test.age)
        assertEquals("Hello from Lua", test.name)
    }

    @LuajExpose
    class ChildClass(
        var age: Int
    ) : ParentClass("childName")

    @LuajExpose
    abstract class ParentClass(
        var name: String
    )
}