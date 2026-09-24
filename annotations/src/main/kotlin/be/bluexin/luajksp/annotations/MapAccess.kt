package be.bluexin.luajksp.annotations

import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/**
 * Runtime object generated bindings expose for a `MutableMap<K, V>`-typed property or return value.
 *
 * Generated bindings never expose a plain (read-only) `Map` to Lua, and never a `MutableMap` with a
 * nullable key or value type - both rejected at build time by the KSP processor - so every
 * [MapAccess] a script sees is always backed by a genuinely mutable, non-null-valued Kotlin map. That
 * guarantee is what makes plain Lua table syntax safe here: `t.map[k]` unambiguously means "the entry
 * for k, or nil if there is none" (never confusable with "present but null"), and `t.map[k] = nil`
 * unambiguously means "remove k", matching how a real Lua table already treats nil-assignment.
 *
 * `t.map[k]`, `t.map[k] = v`, `#t.map`, and `for k, v in pairs(t.map) do ... end` all behave as they
 * would on a real Lua table - there is no separate method surface to learn.
 */
class MapAccess<K : Any, V : Any>(
    private val map: MutableMap<K, V>,
    private val keyCodec: Codec<K>,
    private val valueCodec: Codec<V>,
) : LuaUserdata(map) {

    class Codec<T>(val toLua: (T) -> LuaValue, val fromLua: (LuaValue) -> T)

    init {
        // `pairs(t)` checks `t`'s metatable for a `__pairs` entry before falling back to the `next`/
        // real-LuaTable protocol, so this is enough to make `for k, v in pairs(t.map) do ... end`
        // work without implementing next()'s arbitrary-key-continuation contract.
        setmetatable(tableOf(arrayOf(PAIRS, PairsFunction()), emptyArray()))
    }

    /** Looks up a Lua-side key directly against the backing map. For KSP-generated conversion code, not for scripts. */
    fun rawGet(key: LuaValue): LuaValue = map[keyCodec.fromLua(key)]?.let(valueCodec.toLua) ?: NIL

    /** This map's keys, converted to Lua values. For KSP-generated conversion code, not for scripts. */
    fun keys(): List<LuaValue> = map.keys.map(keyCodec.toLua)

    override fun get(key: LuaValue): LuaValue = rawGet(key)

    override fun set(key: LuaValue, value: LuaValue) {
        val k = keyCodec.fromLua(key)
        if (value.isnil()) map.remove(k) else map[k] = valueCodec.fromLua(value)
    }

    override fun len(): LuaValue = valueOf(map.size)

    private inner class PairsFunction : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            // A snapshot: mutating the map mid-iteration (e.g. `t.map[k] = nil` from within the loop
            // body) must not corrupt this traversal.
            val entries = map.entries.toList()
            val iterator = object : VarArgFunction() {
                var index = 0
                override fun invoke(args: Varargs): Varargs {
                    if (index >= entries.size) return NIL
                    val (k, v) = entries[index++]
                    return varargsOf(arrayOf(keyCodec.toLua(k), valueCodec.toLua(v)))
                }
            }
            return varargsOf(arrayOf(iterator, this@MapAccess, NIL))
        }
    }
}
