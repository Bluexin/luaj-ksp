package be.bluexin.luajksp.annotations

import org.luaj.vm2.LuaValue

interface LKExposed {
    @LuajExclude // TODO : make this work from interface ; we should never need this exposed
    fun toLua(): LuaValue
}