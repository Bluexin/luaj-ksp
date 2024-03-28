package be.bluexin.luajksp.sample

import be.bluexin.luajksp.annotations.LuajExclude
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.sample.access.toLua
import org.junit.jupiter.api.assertThrows
import org.luaj.vm2.LuaError
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AnnotationPriorityTest {

    @BeforeTest
    fun reset() = LuaJTest.resetContexts()

    @Test
    fun `a property with both Expose and Exclude is excluded`() {
        val holder = PriorityTestHolder().toLua()

        assertTrue(
            PriorityTestHolder::text.annotations.any { it is LuajExpose },
            "Expected ${LuajExpose::class.simpleName} annotation to be present"
        )
        assertTrue(
            PriorityTestHolder::text.annotations.any { it is LuajExclude },
            "Expected ${LuajExclude::class.simpleName} annotation to be present"
        )

        assertThrows<LuaError> {
            holder.get(PriorityTestHolder::text.name)
        }
    }

    @LuajExpose
    data class PriorityTestHolder(
        @LuajExpose
        @LuajExclude
        val text: String = UUID.randomUUID().toString()
    )
}