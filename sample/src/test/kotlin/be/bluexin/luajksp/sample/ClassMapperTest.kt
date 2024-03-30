package be.bluexin.luajksp.sample

import be.bluexin.luajksp.annotations.LKMapper
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajMapped
import be.bluexin.luajksp.sample.access.toLua
import org.luaj.vm2.LuaValue
import org.luaj.vm2.LuaValue.valueOf
import kotlin.test.*
import kotlin.test.Test

class ClassMapperTest {

    @BeforeTest
    fun reset() = LuaJTest.resetContexts()

    @Test
    fun `custom mapper on class is used`() {
        val holder = ClassValueTestHolder(id= Identifier("ns", "path"))

        LuaJTest.runTestScript(
            """
                local t = _t.id
                assert_equals('table', type(t))
                assert_equals('ns', t.nameSpace)
                assert_equals('path', t.resource)
                _t.id = {
                    nameSpace = 'newNS',
                    resource = 'newRes'
                }
            """.trimIndent(),
            holder.toLua()
        ).executionAsFailure()

        assertEquals("newNS", holder.id.nameSpace)
        assertEquals("newRes", holder.id.resource)

        LuaJTest.runTestScript(
            """
                local t = _t.id
                assert_equals('table', type(t))
                assert_equals('newNS', t.nameSpace)
                assert_equals('newRes', t.resource)
                _t.id = 'newNewNS:newNewRes'
            """.trimIndent(),
            holder.toLua()
        ).executionAsFailure()

        assertEquals("newNewNS", holder.id.nameSpace)
        assertEquals("newNewRes", holder.id.resource)

        val result1 = LuaJTest.runTestScript(
            """
                local t = _t.id
                assert_equals('table', type(t))
                _t.id = "abc"
            """.trimIndent(),
            holder.toLua()
        )
        assertIs<ScriptResult.Failure>(result1)
        assertContains(result1.errorMessage, "abc is not a valid string identifier !")

        val result2 = LuaJTest.runTestScript(
            """
                local t = _t.id
                assert_equals('table', type(t))
                _t.id = nil
            """.trimIndent(),
            holder.toLua()
        )
        assertIs<ScriptResult.Failure>(result2)
        assertContains(result2.errorMessage, "bad argument: value expected, got nil")
    }

    @LuajExpose
    data class ClassValueTestHolder(
        var id: Identifier
    )

    @LuajMapped(mapper = Mapper::class)
    data class Identifier(
        val nameSpace: String,
        val resource: String
    ) {
        override fun toString() = "$nameSpace:$resource"
    }

    object Mapper: LKMapper<Identifier> {
        override fun toLua(value: Identifier): LuaValue = LuaValue.tableOf(arrayOf(
            valueOf("nameSpace"), valueOf(value.nameSpace),
            valueOf("resource"), valueOf(value.resource),
        ))

        override fun fromLua(value: LuaValue): Identifier = when {
            value.istable() -> Identifier(
                value["nameSpace"].checkjstring(),
                value["resource"].checkjstring()
            )
            value.isstring() -> value.checkjstring().split(':').let {
                check(it.size == 2) { "$value is not a valid string identifier !" }
                Identifier(it.first(), it.last())
            }
            else -> throw IllegalArgumentException("$value is not a valid identifier ! Expected string (namespace:resource) or table ({nameSpace: string, resource: string})")
        }
    }
}