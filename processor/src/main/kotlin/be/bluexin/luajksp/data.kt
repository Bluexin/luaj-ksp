package be.bluexin.luajksp

import be.bluexin.luajksp.annotations.LuajExclude
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.isOpen
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName.Companion.member
import java.util.*

@OptIn(KspExperimental::class)
internal val KSAnnotated.expose: LuajExpose? get() = getAnnotationsByType(LuajExpose::class).singleOrNull()

@OptIn(KspExperimental::class)
internal val KSAnnotated.exposeExternal: LuajExposeExternal? get() = getAnnotationsByType(LuajExposeExternal::class).singleOrNull()

@OptIn(KspExperimental::class)
internal val KSAnnotated.exclude: LuajExclude? get() = getAnnotationsByType(LuajExclude::class).singleOrNull()

internal val KSDeclaration.accessClassName get() = ClassName(
    (packageName.asString().takeIf(String::isNotEmpty)?.let { "$it." }).orEmpty() + "access",
    simpleName.getShortName() + "Access"
)

internal val LuaUserdataClassName = ClassName("org.luaj.vm2", "LuaUserdata")
internal val LuaValueClassName = LuaUserdataClassName.peerClass("LuaValue")
internal val LuaValueOfName = LuaUserdataClassName.member("valueOf")
internal val LuaVarargsOfName = LuaUserdataClassName.member("varargsOf")
internal val LuaFunctionClassName = LuaUserdataClassName.peerClass("LuaFunction")
internal val CoerceJavaToLuaName = ClassName("org.luaj.vm2.lib.jse", "CoerceJavaToLua").member("coerce")
internal val ZeroArgFunctionName = ClassName("org.luaj.vm2.lib", "ZeroArgFunction")
internal val OneArgFunctionName = ZeroArgFunctionName.peerClass("OneArgFunction")
internal val TwoArgFunctionName = ZeroArgFunctionName.peerClass("TwoArgFunction")
internal val ThreeArgFunctionName = ZeroArgFunctionName.peerClass("ThreeArgFunction")
internal val VarArgFunctionName = ZeroArgFunctionName.peerClass("VarArgFunction")

internal sealed interface ExposedData {
    val simpleName: String
    val docString: String?
    val parentDeclaration: KSDeclaration?
    val source: KSNode

    fun mergeWith(other: ExposedData): ExposedData =
        throw UnsupportedOperationException("Cannot merge $this with $other")
}

internal sealed interface ExposedPropertyLike: ExposedData {
    val hasGetter: Boolean
    val hasSetter: Boolean
    val type: KSTypeReference

    data class FromKSProperty(
        private val property: KSPropertyDeclaration
    ) : ExposedPropertyLike {
        override val hasGetter get() = property.getter != null && Modifier.PRIVATE !in property.getter!!.modifiers
        override val hasSetter get() = property.setter != null && Modifier.PRIVATE !in property.setter!!.modifiers
        override val simpleName get() = property.simpleName.asString()
        override val type get() = property.type
        override val parentDeclaration get() = property.parentDeclaration
        override val docString get() = property.docString
        override val source get() = property
    }

    @Suppress("DataClassPrivateConstructor")
    data class FromKSFunctions private constructor(
        override val simpleName: String,
        override val type: KSTypeReference,
        private var getter: KSFunctionDeclaration?,
        private var setter: KSFunctionDeclaration?,
    ) : ExposedPropertyLike {
        override val hasGetter get() = getter != null
        override val hasSetter get() = setter != null
        override val parentDeclaration get() = getter?.parentDeclaration ?: setter?.parentDeclaration
        override val docString
            get() = listOfNotNull(
                getter?.docString?.let { "Getter: ${it.trim()}" },
                setter?.docString?.let { "Setter: ${it.trim()}" }
            ).joinToString("\n").replace('@', '-')
        override val source get() = getter ?: setter!!

        override fun mergeWith(other: ExposedData): FromKSFunctions {
            require(other is FromKSFunctions) { "Cannot merge $this with $other" }
            require(simpleName == other.simpleName) { "Name mismatch merging $this with $other" }
            require(type.resolve() == other.type.resolve()) { "Type mismatch merging $this with $other" }

            if (getter == null) {
                require(other.getter != null && other.setter == null) { "Getter/Setter mismatch merging $this with $other" }
                getter = other.getter
            } else if (setter == null) {
                require(other.setter != null && other.getter == null) { "Getter/Setter mismatch merging $this with $other" }
                setter = other.setter
            } else error("Cannot merge already complete $this")

            return this
        }

        companion object {
            private val String.toPropName: String?
                get() = when {
                    startsWith("get") -> substringAfter("get").replaceFirstChar { it.lowercase(Locale.getDefault()) }
                    startsWith("set") -> substringAfter("set").replaceFirstChar { it.lowercase(Locale.getDefault()) }
                    else -> null
                }

            fun fromGetter(getter: KSFunctionDeclaration): FromKSFunctions? {
                if (getter.parameters.isNotEmpty()) return null
                val propName = getter.simpleName.asString().toPropName ?: return null
                return FromKSFunctions(
                    simpleName = propName,
                    type = getter.returnType ?: error("Unable to resolve return type of $getter"),
                    getter = getter,
                    setter = null
                )
            }

            fun fromSetter(setter: KSFunctionDeclaration): FromKSFunctions? {
                val propName = setter.simpleName.asString().toPropName ?: return null
                val param = setter.parameters.singleOrNull() ?: return null
                return FromKSFunctions(
                    simpleName = propName,
                    type = param.type,
                    getter = null,
                    setter = setter
                )
            }

            fun fromFunction(fn: KSFunctionDeclaration): FromKSFunctions? = when (fn.origin) {
                Origin.JAVA_LIB, Origin.JAVA -> fromGetter(fn) ?: fromSetter(fn)
                else -> null
            }
        }
    }
}

internal data class ExposedFunction(
    val declaration: KSFunctionDeclaration
): ExposedData {
    override val simpleName get() = declaration.simpleName.asString()
    override val docString get() = declaration.docString
    override val parentDeclaration get() = declaration.parentDeclaration
    override val source get() = declaration
}
