package be.bluexin.luajksp

import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.visitor.KSEmptyVisitor

internal sealed class LuajVisitor(
    protected val logger: KSPLogger
) : KSEmptyVisitor<MutableMap<String, PropertyLike>, Map<String, PropertyLike>>() {
    protected var rootDeclaration: KSDeclaration? = null
    private var hasVisitedClass = false

    override fun visitClassDeclaration(
        classDeclaration: KSClassDeclaration,
        data: MutableMap<String, PropertyLike>
    ): Map<String, PropertyLike> {
        if (hasVisitedClass) return data
        else hasVisitedClass = true
        classDeclaration.declarations.forEach { it.accept(this, data) }
        return data
    }

    override fun visitFunctionDeclaration(
        function: KSFunctionDeclaration,
        data: MutableMap<String, PropertyLike>
    ): Map<String, PropertyLike> {
        logger.logging("Visiting fn $function")
        if (function.include) {
            // TODO : handle non property-like functions
            PropertyLike.FromKSFunctions.fromFunction(function)
                ?.let(data::addPropertyLike)
        }
        return data
    }

    override fun visitPropertyDeclaration(
        property: KSPropertyDeclaration,
        data: MutableMap<String, PropertyLike>
    ): Map<String, PropertyLike> {
        logger.logging("Visiting property $property")
        if (property.isPublic() && property.include) data.addPropertyLike(PropertyLike.FromKSProperty(property))
        return data
    }

    abstract val KSPropertyDeclaration.include: Boolean
    abstract val KSFunctionDeclaration.include: Boolean

    override fun defaultHandler(
        node: KSNode,
        data: MutableMap<String, PropertyLike>
    ): Map<String, PropertyLike> {
        logger.logging("Visiting $node")
        return data
    }

    class LuajInternalVisitor(
        private val expose: LuajExpose,
        logger: KSPLogger
    ) : LuajVisitor(logger) {

        override val KSPropertyDeclaration.include: Boolean
            get() = when (this@LuajInternalVisitor.expose.includeType) {
                LuajExpose.IncludeType.OPT_IN -> exclude == null && expose != null
                LuajExpose.IncludeType.OPT_OUT -> exclude == null
            }

        override val KSFunctionDeclaration.include: Boolean
            get() = when (this@LuajInternalVisitor.expose.includeType) {
                LuajExpose.IncludeType.OPT_IN -> exclude == null && expose != null
                LuajExpose.IncludeType.OPT_OUT -> exclude == null
            }

        override fun visitClassDeclaration(
            classDeclaration: KSClassDeclaration,
            data: MutableMap<String, PropertyLike>
        ): Map<String, PropertyLike> {
            if (rootDeclaration != null) return data
            logger.logging("Visiting $classDeclaration")

            rootDeclaration = classDeclaration
            return super.visitClassDeclaration(classDeclaration, data)
        }
    }

    class LuajExternalVisitor(
        private val expose: LuajExposeExternal,
        logger: KSPLogger
    ) : LuajVisitor(logger) {

        override val KSPropertyDeclaration.include: Boolean
            get() = simpleName.asString() in this@LuajExternalVisitor.expose.whitelist

        override val KSFunctionDeclaration.include: Boolean
            get() = simpleName.asString() in this@LuajExternalVisitor.expose.whitelist

        override fun visitTypeAlias(
            typeAlias: KSTypeAlias,
            data: MutableMap<String, PropertyLike>
        ): Map<String, PropertyLike> {
            if (rootDeclaration != null) return data
            logger.logging("Visiting $typeAlias")
            if (expose.whitelist.isEmpty()) logger.warn("Empty ${LuajExposeExternal::class.simpleName} whitelist, is this intended ?")

            rootDeclaration = typeAlias
            return typeAlias.type.resolve().declaration.accept(this, data)
        }
    }
}

private fun MutableMap<String, PropertyLike>.addPropertyLike(new: PropertyLike) {
    compute(new.simpleName) { _, existing ->
        existing?.mergeWith(new) ?: new
    }
}