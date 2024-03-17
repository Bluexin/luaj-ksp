package be.bluexin.luajksp.sample

import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.sample.access.toLua
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*
import kotlin.random.Random

private val logger = KotlinLogging.logger {  }

fun main() {
    val test = Test()
    logger.info { "Initial : $test" }

    LuaJTest.runScript("test", """
        --- @type Test
        local t = testing.testValue
        print("type of t: " .. type(t))
        print(t.text)
        print(tostring(t.num))
        t.mtext = "hello from lua"
        t.mnum = "5"
    """.trimIndent()) {
        mapOf(
            "testValue" to test.toLua(),
            "wrapper" to TestWrapper(test).toLua()
        )
    }

    logger.info { "Final : $test" }
}

@LuajExpose
data class TestWrapper(
    @LuajExpose
    var test: Test
)

@LuajExpose
data class Test(
    /**
     * A test's text value
     */
    @LuajExpose
    val text: String = UUID.randomUUID().toString(),
    @LuajExpose
    val num: Int = Random.nextInt(),
    @LuajExpose
    val bool: Boolean = Random.nextBoolean(),
    @LuajExpose
    var mtext: String = UUID.randomUUID().toString(),
    @LuajExpose
    var mnum: Int = Random.nextInt(),
    @LuajExpose
    var mbool: Boolean = Random.nextBoolean(),
)


