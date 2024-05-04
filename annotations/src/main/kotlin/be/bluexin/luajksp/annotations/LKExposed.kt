package be.bluexin.luajksp.annotations

import org.luaj.vm2.LuaValue

interface LKExposed {
    fun toLua(): LuaValue
}