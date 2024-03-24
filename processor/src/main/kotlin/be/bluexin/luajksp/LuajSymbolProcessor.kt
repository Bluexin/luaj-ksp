package be.bluexin.luajksp

import be.bluexin.luajksp.annotations.LuajExclude
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExpose.IncludeType.OPT_IN
import be.bluexin.luajksp.annotations.LuajExpose.IncludeType.OPT_OUT
import be.bluexin.luajksp.annotations.LuajExposeExternal
import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import org.intellij.lang.annotations.Language
import java.io.OutputStream
import java.util.*

class LuajSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        return processInternal(resolver) + processExternal(resolver)
    }

    private fun processInternal(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(LuajExpose::class.qualifiedName!!)
        val remaining = symbols.filter { !it.validate() }.toList()

        symbols.filter { (it is KSClassDeclaration) && it.validate() }.forEach {
            it.accept(LuajInternalVisitor(it.expose!!), Unit)
        }

        return remaining
    }

    private fun processExternal(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(LuajExposeExternal::class.qualifiedName!!)
        val remaining = symbols.filter { !it.validate() }.toList()

        symbols.filter { (it is KSTypeAlias) && it.validate() }.forEach {
            it.accept(LuajExternalVisitor(it.exposeExternal!!), Unit)
        }

        return remaining
    }

    @OptIn(KspExperimental::class)
    private val KSAnnotated.expose: LuajExpose? get() = getAnnotationsByType(LuajExpose::class).singleOrNull()

    @OptIn(KspExperimental::class)
    private val KSAnnotated.exposeExternal: LuajExposeExternal? get() = getAnnotationsByType(LuajExposeExternal::class).singleOrNull()

    @OptIn(KspExperimental::class)
    private val KSAnnotated.exclude: LuajExclude? get() = getAnnotationsByType(LuajExclude::class).singleOrNull()

    @OptIn(KspExperimental::class)
    private abstract inner class LuajVisitor : KSVisitorVoid() {
        private val properties = mutableMapOf<String, PropertyLike>()
        private fun addPropertyLike(new: PropertyLike) {
            properties.compute(new.simpleName) { _, existing ->
                existing?.mergeWith(new) ?: new
            }
        }

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            classDeclaration.declarations.forEach { it.accept(this, data) }
        }

        protected fun generateKotlin(forDeclaration: KSDeclaration) {
            val receiverClassName = forDeclaration.simpleName.asString()

            val targetClassName = "${forDeclaration.simpleName.asString()}Access"
            val targetPackage = "${forDeclaration.packageName.asString()}.access"
            logger.warn("Generating $targetPackage.$targetClassName for $receiverClassName")
            logger.warn("Properties : $properties")

            codeGenerator.createNewFile(
                Dependencies(true, forDeclaration.containingFile!!),
                targetPackage,
                targetClassName
            ).use { file ->
                file.appendKotlin(
                    """
                    package $targetPackage

                    import org.luaj.vm2.LuaUserdata
                    import org.luaj.vm2.LuaValue
                    import org.luaj.vm2.lib.jse.CoerceJavaToLua
                    
                    import ${forDeclaration.qualifiedName?.asString() ?: "ERROR"}

                    /**
                     * Generated with luaj-ksp
                     */
                    @Suppress("RedundantSuppression", "ConvertToStringTemplate")
                    class $targetClassName(val receiver: $receiverClassName): LuaUserdata(receiver, /*TODO: figure out metatable?*/) {
                        override fun set(key: LuaValue, value: LuaValue) {
                            when (key.checkjstring()) {
                            ${
                        properties.values.filter(PropertyLike::hasSetter)
                            .joinToString(separator = "\n                            ") {
                                val sn = it.simpleName
                                """    "$sn" -> receiver.$sn = ${"value".luaValueToKotlin(it)}"""
                            }
                    }
                                else -> error("Cannot set " + key + " on $receiverClassName")
                            }
                        }

                        override fun get(key: LuaValue): LuaValue = when (key.checkjstring()) {
                        ${
                        properties.values.filter(PropertyLike::hasGetter)
                            .joinToString(separator = "\n                        ") {
                                "    ${it.javaToLua()}"
                            }
                    }
                            else -> error("Cannot get " + key + " on $receiverClassName")
                        }
                    }

                    fun $receiverClassName.toLua() = $targetClassName(this)

                """.trimIndent()
                )
            }
        }

        protected fun generateLua(classDeclaration: KSDeclaration) {
            val targetClassName = classDeclaration.simpleName.asString()
            codeGenerator.createNewFileByPath(
                Dependencies(true, classDeclaration.containingFile!!),
                "lualib/$targetClassName",
                extensionName = "lua",
            ).use { file ->
                file.appendLua(
                    """--- Generated with luaj-ksp
                    |--- ${classDeclaration.docString.kdocToLDoc("    ")}
                    |--- @class $targetClassName
                    |$targetClassName = {${
                        properties.values.joinToString(separator = ",\n") {
                            """
                            |    --- ${it.docString.kdocToLDoc("    ")}
                            |    --- ${if (it.hasSetter) "mutable" else "immutable"}
                            |    --- @type ${luaType(it.type)}
                            |    ${it.simpleName} = nil"""
                        }
                    }
                    |}
                    """.trimMargin()
                )
            }
        }

        private fun String.luaValueToKotlin(prop: PropertyLike): String {
            val type = prop.type.resolve()
            val receiver = if (type.nullability == Nullability.NOT_NULL) "$this.checknotnil()" else this
            return buildString {
                if (type.nullability == Nullability.NULLABLE) append("if (${this@luaValueToKotlin}.isnil()) null else ")

                when (prop.type.toString()) {
                    "String" -> append(receiver).append(".checkjstring()")
                    "Int" -> append(receiver).append(".checkint()")
                    "Long" -> append(receiver).append(".checklong()")
                    "Boolean" -> append(receiver).append(".checkboolean()")
                    "Double" -> append(receiver).append(".checkdouble()")
                    else -> {
                        val typeDeclaration = type.declaration
                        if (typeDeclaration.isAnnotationPresent(LuajExpose::class)) {
                            val typeFqn =
                                "${typeDeclaration.packageName.asString()}.access.${typeDeclaration.simpleName.asString()}Access"
                            append('(')
                            append(receiver)
                            append(".checkuserdata(")
                            append(typeFqn)
                            append("::class.java) as ")
                            append(typeFqn)
                            append(").receiver")
                        } else prop.unsupportedTypeError()
                    }
                }
            }
        }

        private fun PropertyLike.javaToLua(): String {
            return when (type.toString()) {
                "String", "Int", "Long", "Boolean", "Double" -> "\"$simpleName\" -> CoerceJavaToLua.coerce(receiver.$simpleName)"
                else -> {
                    val typeDeclaration = type.resolve().declaration
                    if (typeDeclaration.isAnnotationPresent(LuajExpose::class)) {
                        val typeFqn =
                            "${typeDeclaration.packageName.asString()}.access.${typeDeclaration.simpleName.asString()}Access"
                        "\"$simpleName\" -> $typeFqn(receiver.$simpleName)"
                    } else this.unsupportedTypeError()
                }
            }
        }

        private fun PropertyLike.unsupportedTypeError(): Nothing =
            error("Unsupported type for ${this.parentDeclaration}.$this: $type")

        private fun luaType(type: KSTypeReference) = when (val ts = type.toString()) {
            "String" -> "string"
            "Int", "Double", "Long" -> "number"
            "Boolean" -> "boolean"
            else -> ts
        }

        private fun String?.kdocToLDoc(indent: String = "") =
            this?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.replace("\n", "\n$indent---")
                ?: "No documentation provided"

        override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: Unit) {
            logger.warn("Visiting fn $function")
            if (function.include) {
                // TODO : handle non property-like functions
                PropertyLike.FromKSFunctions.fromFunction(function)
                    ?.let(::addPropertyLike)
            }
        }

        override fun visitPropertyGetter(getter: KSPropertyGetter, data: Unit) {
            logger.warn("Visiting getter $getter")
            getter.expose?.let { expose ->
                logger.warn("Found $expose")
            }
        }

        override fun visitPropertyAccessor(accessor: KSPropertyAccessor, data: Unit) {
            logger.warn("Visiting accessor $accessor")
            accessor.expose?.let { expose ->
                logger.warn("Found $expose")
            }
        }

        override fun visitPropertyDeclaration(property: KSPropertyDeclaration, data: Unit) {
            logger.warn("Visiting property $property")
            if (property.isPublic() && property.include) addPropertyLike(PropertyLike.FromKSProperty(property))
        }

        abstract val KSPropertyDeclaration.include: Boolean
        abstract val KSFunctionDeclaration.include: Boolean

        override fun visitPropertySetter(setter: KSPropertySetter, data: Unit) {
            logger.warn("Visiting setter $setter")
            setter.expose?.let { expose ->
                logger.warn("Found $expose")
            }
        }

        private fun OutputStream.appendKotlin(@Language("kt") str: String) {
            this.write(str.toByteArray())
        }

        private fun OutputStream.appendLua(@Language("lua") str: String) = appendKotlin(str)
    }

    private inner class LuajInternalVisitor(private val expose: LuajExpose) : LuajVisitor() {
        override val KSPropertyDeclaration.include: Boolean
            get() = when (this@LuajInternalVisitor.expose.includeType) {
                OPT_IN -> exclude == null && expose != null
                OPT_OUT -> exclude == null
            }

        override val KSFunctionDeclaration.include: Boolean
            get() = when (this@LuajInternalVisitor.expose.includeType) {
                OPT_IN -> exclude == null && expose != null
                OPT_OUT -> exclude == null
            }

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            logger.warn("Visiting $classDeclaration")
            super.visitClassDeclaration(classDeclaration, data)

            generateKotlin(classDeclaration)
            generateLua(classDeclaration)
        }
    }

    private inner class LuajExternalVisitor(private val expose: LuajExposeExternal) : LuajVisitor() {
        override val KSPropertyDeclaration.include: Boolean
            get() = simpleName.asString() in this@LuajExternalVisitor.expose.whitelist

        override val KSFunctionDeclaration.include: Boolean
            get() = simpleName.asString() in this@LuajExternalVisitor.expose.whitelist

        override fun visitTypeAlias(typeAlias: KSTypeAlias, data: Unit) {
            logger.warn("Visiting $typeAlias")
            typeAlias.type.resolve().declaration.accept(this, data)

            generateKotlin(typeAlias)
            generateLua(typeAlias)
        }
    }

    private sealed interface PropertyLike {
        val hasGetter: Boolean
        val hasSetter: Boolean
        val simpleName: String
        val type: KSTypeReference
        val parentDeclaration: KSDeclaration?
        val docString: String?
        
        fun mergeWith(other: PropertyLike): PropertyLike = throw UnsupportedOperationException("Cannot merge $this with $other")

        data class FromKSProperty(
            private val property: KSPropertyDeclaration
        ) : PropertyLike {
            override val hasGetter get() = property.getter != null
            override val hasSetter get() = property.setter != null
            override val simpleName get() = property.simpleName.asString()
            override val type get() = property.type
            override val parentDeclaration get() = property.parentDeclaration
            override val docString get() = property.docString
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
            override val docString get() = listOfNotNull(
                getter?.docString?.let { "Getter: ${it.trim()}" },
                setter?.docString?.let { "Setter: ${it.trim()}" }
            ).joinToString("\n ").replace('@', '-')

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

    class Provider : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment) = LuajSymbolProcessor(
            environment.codeGenerator, environment.logger
        )
    }
}