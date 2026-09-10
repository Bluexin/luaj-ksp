package be.bluexin.luajksp.annotations

import kotlin.reflect.KClass

/**
 * Mark a class, property, or function for exposition to LuaJ.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class LuajExpose(
    /**
     * How to handle inclusion of contained properties and functions.
     * Specifying this on a property or function will have no effect.
     */
    val includeType: IncludeType = IncludeType.OPT_OUT
) {

    enum class IncludeType {
        /**
         * Include only fields marked with [LuajExpose].
         */
        OPT_IN,

        /**
         * Include all fields except when marked with [LuajExclude].
         */
        OPT_OUT
    }
}

/**
 * Mark a property or function to be hidden from LuaJ.
 * Higher priority over [LuajExpose]
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class LuajExclude

/**
 * Mark a typealias for exposition to LuaJ.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.TYPEALIAS)
annotation class LuajExposeExternal(
    /**
     * Whitelist of properties to expose
     */
    vararg val whitelist: String
)

/**
 * Mark a class as a Lua library module.
 *
 * The annotated class will get a generated `<ClassName>Lib : TwoArgFunction` that installs a table
 * under a global with the given [name], following the standard Lua library pattern.
 *
 * Only members annotated with [LuajExpose] are included; only functions are supported for now.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class LuajLib(
    /**
     * Name of the global the library registers itself under.
     */
    val name: String
)

/**
 * Marker for custom mapping.
 * When defined on a class or typealias, usages of that type will use the specified [mapper].
 */
@Retention(AnnotationRetention.RUNTIME) // TODO : don't forget to test on TYPE
@Target(AnnotationTarget.TYPEALIAS, AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class LuajMapped(
    val mapper: KClass<out LKMapper<*>>,
    /**
     * Import statement to use for this type.
     */
    val import: String = ""
)
