package be.bluexin.luajksp

import be.bluexin.luajksp.annotations.LuajExclude
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.symbol.*
import java.util.*

@OptIn(KspExperimental::class)
internal val KSAnnotated.expose: LuajExpose? get() = getAnnotationsByType(LuajExpose::class).singleOrNull()

@OptIn(KspExperimental::class)
internal val KSAnnotated.exposeExternal: LuajExposeExternal? get() = getAnnotationsByType(LuajExposeExternal::class).singleOrNull()

@OptIn(KspExperimental::class)
internal val KSAnnotated.exclude: LuajExclude? get() = getAnnotationsByType(LuajExclude::class).singleOrNull()

internal class AccessClassFQN(private val originalFQN: KSDeclaration) : KSName {
    override fun asString() = "${getQualifier()}.${getShortName()}"
    override fun getQualifier() = "${(originalFQN.packageName.asString().takeIf(String::isNotEmpty)?.let { "$it." }).orEmpty()}access"
    override fun getShortName() = "${originalFQN.simpleName.getShortName()}Access"
}

internal object LuaUserdataFQN : KSName {
    override fun asString() = "${getQualifier()}.${getShortName()}"
    override fun getQualifier() = "org.luaj.vm2"
    override fun getShortName() = "LuaUserdata"
}

internal sealed interface PropertyLike {
    val hasGetter: Boolean
    val hasSetter: Boolean
    val simpleName: String
    val type: KSTypeReference
    val parentDeclaration: KSDeclaration?
    val docString: String?
    val source: KSNode

    fun mergeWith(other: PropertyLike): PropertyLike =
        throw UnsupportedOperationException("Cannot merge $this with $other")

    data class FromKSProperty(
        private val property: KSPropertyDeclaration
    ) : PropertyLike {
        override val hasGetter get() = property.getter != null
        override val hasSetter get() = property.setter != null
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
    ) : PropertyLike {
        override val hasGetter get() = getter != null
        override val hasSetter get() = setter != null
        override val parentDeclaration get() = getter?.parentDeclaration ?: setter?.parentDeclaration
        override val docString
            get() = listOfNotNull(
                getter?.docString?.let { "Getter: ${it.trim()}" },
                setter?.docString?.let { "Setter: ${it.trim()}" }
            ).joinToString("\n ").replace('@', '-')
        override val source get() = getter ?: setter!!

        override fun mergeWith(other: PropertyLike): FromKSFunctions {
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