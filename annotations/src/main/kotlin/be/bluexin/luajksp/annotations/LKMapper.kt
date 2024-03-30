package be.bluexin.luajksp.annotations

import org.luaj.vm2.LuaValue

/**
 * Interface to implement by an object (or class with no-arg constructor) for custom LuaJ mapping.
 * Nullability is handled by the code generation.
 */
interface LKMapper<JType: Any> {

    fun toLua(value: JType): LuaValue

    /**
     * @param value never nil
     */
    fun fromLua(value: LuaValue): JType
}