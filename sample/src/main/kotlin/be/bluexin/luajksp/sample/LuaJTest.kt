package be.bluexin.luajksp.sample

import io.github.oshai.kotlinlogging.KotlinLogging
import org.intellij.lang.annotations.Language
import org.luaj.vm2.*
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import org.luaj.vm2.lib.jse.JseStringLib

// TODO : check BCEL for lua-to-(jvm bytecode) compilation -- see LuaJC::install
object LuaJTest {
    private val logger = KotlinLogging.logger {  }

    private val scriptInstructionsLimit = LuaValue.valueOf(50_000) // TODO: evaluate proper limit

    private val serverGlobals = Globals().apply {
        load(JseBaseLib())
        load(PackageLib())
        load(JseStringLib())
        load(JseMathLib())
        LoadState.install(this)
        LuaC.install(this)
//        LuaJC.install(this)
        LuaString.s_metatable = ReadOnlyLuaTable(LuaString.s_metatable)
    }

    private val scriptGlobals = mutableMapOf<String, ScriptEnvironment>()

    /**
     * Enables debug for hooks to work, but remove it from user space.
     * Returns a reference to sethook
     */
    private fun Globals.enableDebugSafely(): LuaValue {
        load(DebugLib())
        val setHook = get("debug").get("sethook")
        set("debug", LuaValue.NIL)
        return setHook
    }

    private fun getEnvFor(script: String, context: () -> Map<String, LuaValue>) = scriptGlobals.getOrPut(script) {
        val globals = Globals().apply {
            load(JseBaseLib())
            load(PackageLib())
            load(Bit32Lib())
            load(TableLib())
            load(JseStringLib())
            load(JseMathLib())
            load(TestLib(context()))
        }
        val setHook = globals.enableDebugSafely()

        ScriptEnvironment(globals, setHook)
    }

    private val hookFunc = object : ZeroArgFunction() {
        override fun call(): LuaValue {
            throw IllegalStateException("Script overran resource limits")
        }
    }

    fun loadChunk(key: String, @Language("lua") snippet: String): LuaValue {
        return serverGlobals.load(snippet, "=$key")
    }

    fun runScript(key: String, @Language("lua") snippet: String, context: () -> Map<String, LuaValue> = ::emptyMap): ScriptResult {
        val (userGlobals, setHook) = getEnvFor(key, context)
        val chunk = serverGlobals.load(snippet, "=$key", userGlobals)
        val userThread = LuaThread(userGlobals, chunk)
        setHook(
            LuaValue.varargsOf(
                arrayOf(userThread, hookFunc, LuaValue.EMPTYSTRING, scriptInstructionsLimit)
            )
        )

        val result = userThread.resume(
            LuaValue.varargsOf(
                arrayOf(
                    LuaValue.valueOf("luaj-ksp-sample-0.0.1.INDEV"),
                    LuaValue.valueOf(key),
                )
            )
        )

        return if (result.checkboolean(1)) ScriptResult.Success(snippet)
        else ScriptResult.Failure(snippet, result.checkjstring(2))
    }

    fun resetContexts() {
        scriptGlobals.clear()
    }

    private data class ScriptEnvironment(
        val globals: Globals,
        val setHook: LuaValue
    )
}

sealed interface ScriptResult {
    val snippet: String

    data class Success(
        override val snippet: String
    ): ScriptResult
    data class Failure(
        override val snippet: String,
        val errorMessage: String
    ): ScriptResult
}

private class ReadOnlyLuaTable(table: LuaValue) : LuaTable() {
    init {
        presize(table.length(), 0)
        var n = table.next(NIL)
        while (!n.arg1().isnil()) {
            val key = n.arg1()
            val value = n.arg(2)
            super.rawset(key, if (value.istable()) ReadOnlyLuaTable(value) else value)
            n = table
                .next(n.arg1())
        }
    }

    override fun setmetatable(metatable: LuaValue): LuaValue {
        return error("table is read-only")
    }

    override fun set(key: Int, value: LuaValue) {
        error("table is read-only")
    }

    override fun rawset(key: Int, value: LuaValue) {
        error("table is read-only")
    }

    override fun rawset(key: LuaValue, value: LuaValue) {
        error("table is read-only")
    }

    override fun remove(pos: Int): LuaValue {
        return error("table is read-only")
    }
}

class TestLib(private val context: Map<String, LuaValue>) : TwoArgFunction() {

    override fun call(modname: LuaValue, env: LuaValue): LuaValue {
        val themeTable = LuaTable()
        context.forEach { (k, v) ->
            themeTable[k] = v
        }
        env["testing"] = themeTable
        if (!env["package"].isnil()) {
            env["package"]["loaded"]["testing"] = themeTable
        }

        return themeTable
    }
}
