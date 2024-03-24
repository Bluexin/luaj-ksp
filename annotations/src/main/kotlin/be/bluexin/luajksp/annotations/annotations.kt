package be.bluexin.luajksp.annotations

/**
 * Mark a class, property or function for exposition to LuaJ.
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
 * Marks a typealias for exposition to LuaJ.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.TYPEALIAS)
annotation class LuajExposeExternal(
    /**
     * Whitelist of properties to expose
     */
    vararg val whitelist: String
)
