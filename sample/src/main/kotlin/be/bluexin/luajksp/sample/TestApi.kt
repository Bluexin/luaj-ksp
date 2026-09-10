package be.bluexin.luajksp.sample

import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajLib

/**
 * A sample library used to test the [LuajLib] support in luaj-ksp.
 *
 * The generated `TestApiLib : TwoArgFunction` registers a `api` table implementing these functions.
 */
@LuajLib(name = "api")
class TestApi {

    /**
     * Greets the given [name].
     */
    @LuajExpose
    fun greet(name: String): String = "Hello $name"

    /**
     * Doubles the given [value].
     */
    @LuajExpose
    fun double(value: Int): Int = value * 2

    /**
     * Applies [transform] to [value].
     */
    @LuajExpose
    fun apply(value: Int, transform: (Int) -> Int): Int = transform(value)

    /**
     * Returns a list of [Test] instances.
     */
    @LuajExpose
    fun bulk(): List<Test> = listOf(Test(), Test())
}