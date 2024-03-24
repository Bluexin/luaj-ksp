package be.bluexin.luajksp

import be.bluexin.luajksp.annotations.LuajExclude
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExpose.IncludeType.OPT_IN
import be.bluexin.luajksp.annotations.LuajExpose.IncludeType.OPT_OUT
import be.bluexin.luajksp.annotations.LuajExposeExternal
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import org.intellij.lang.annotations.Language
import java.io.OutputStream

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
        private val properties = mutableListOf<KSPropertyDeclaration>()

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
                        properties.filter { it.isMutable }
                            .joinToString(separator = "\n                            ") {
                                val sn = it.simpleName.getShortName()
                                """    "$sn" -> receiver.$sn = ${"value".luaValueToKotlin(it)}"""
                            }
                    }
                                else -> error("Cannot set " + key + " on $receiverClassName")
                            }
                        }

                        override fun get(key: LuaValue): LuaValue = when (key.checkjstring()) {
                        ${
                        properties.filter { it.getter != null }
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
                        properties.joinToString(separator = ",\n") {
                            """
                            |    --- ${it.docString.kdocToLDoc("    ")}
                            |    --- ${if (it.isMutable) "mutable" else "immutable"}
                            |    --- @type ${luaType(it.type)}
                            |    ${it.simpleName.getShortName()} = nil"""
                        }
                    }
                    |}
                    """.trimMargin()
                )
            }
        }

        private fun String.luaValueToKotlin(prop: KSPropertyDeclaration): String {
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

        private fun KSPropertyDeclaration.javaToLua(): String {
            val sn = simpleName.getShortName()
            return when (type.toString()) {
                "String", "Int", "Long", "Boolean", "Double" -> "\"$sn\" -> CoerceJavaToLua.coerce(receiver.$sn)"
                else -> {
                    val typeDeclaration = type.resolve().declaration
                    if (typeDeclaration.isAnnotationPresent(LuajExpose::class)) {
                        val typeFqn =
                            "${typeDeclaration.packageName.asString()}.access.${typeDeclaration.simpleName.asString()}Access"
                        "\"$sn\" -> $typeFqn(receiver.$sn)"
                    } else this.unsupportedTypeError()
                }
            }
        }

        private fun KSPropertyDeclaration.unsupportedTypeError(): Nothing =
            error("Unsupported type for ${this.parentDeclaration}.$this: ${this.type}")

        private fun luaType(type: KSTypeReference) = when (val ts = type.toString()) {
            "String" -> "string"
            "Int", "Double", "Long" -> "number"
            "Boolean" -> "boolean"
            else -> ts
        }

        private fun String?.kdocToLDoc(indent: String = "") =
            this?.trim()?.replace("\n", "\n$indent--") ?: "No documentation provided"

        override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: Unit) {
            logger.warn("Visiting fn $function")
            function.expose?.let { expose ->
                logger.warn("Found $expose")
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
            if (property.include) properties += property
        }

        abstract val KSPropertyDeclaration.include: Boolean

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

        override fun visitTypeAlias(typeAlias: KSTypeAlias, data: Unit) {
            logger.warn("Visiting $typeAlias")
            typeAlias.type.resolve().declaration.accept(this, data)

            generateKotlin(typeAlias)
            generateLua(typeAlias)
        }
    }

    class Provider : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment) = LuajSymbolProcessor(
            environment.codeGenerator, environment.logger
        )
    }
}