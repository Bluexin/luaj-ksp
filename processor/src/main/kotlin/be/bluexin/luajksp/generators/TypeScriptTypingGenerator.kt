package be.bluexin.luajksp.generators

import be.bluexin.luajksp.*
import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajMapped
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.ksp.toClassName
import java.io.OutputStream

private typealias Import = Pair<String, String>

/**
 * Generates minimal TypeScript declaration files (.d.ts) for use with TypeScriptToLua.
 * The goal is parity with the existing EmmyLua-ish typings, not full fidelity.
 */
@OptIn(KspExperimental::class)
internal class TypeScriptTypingGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {

    fun generate(
        forDeclaration: KSDeclaration,
        properties: Map<String, ExposedData>
    ) {
        val targetClassName = forDeclaration.simpleName.asString()

        logger.info("Generating TypeScript typing for $targetClassName", forDeclaration)
        logger.logging("Properties : $properties")

        // Collect import dependencies
        val referencedTypes = collectImports(forDeclaration, properties)
            .filterNot { it.first == targetClassName }
            .toSortedSet { a, b -> a.first.compareTo(b.first) }

        // We generate a .d.ts per exposed root symbol.
        // Use an interface as it best maps the exposed API shape.
        codeGenerator.createNewFileByPath(
            Dependencies(true, forDeclaration.containingFile!!),
            path = "typings/$targetClassName",
            extensionName = "d.ts",
        ).use { file ->
            file.appendTs(
                """
                |// Generated with luaj-ksp
                |${forDeclaration.docString.kdocToTsDoc(indent = "")}
                |${referencedTypes.joinToString("\n") { "import {${it.first}} from \"./${it.second}\";" }}
                |
                |/** @noSelf **/
                |${buildInterfaceHeader(forDeclaration, targetClassName)} {
                |${
                    properties.values.joinToString(separator = "\n") { accessor ->
                        when (accessor) {
                            is ExposedPropertyLike -> {
                                val doc = accessor.docString.kdocToTsDoc(indent = "    ").takeIf(String::isNotBlank)
                                val ro = if (accessor.hasSetter) "" else "readonly "
                                val type = tsType(accessor.type)
                                listOfNotNull(
                                    doc, "    ${ro}${accessor.simpleName}: ${type};"
                                ).joinToString(separator = "\n")
                            }

                            is ExposedFunction -> {
                                val sig = functionPrototypeSignature(accessor.declaration)
                                val doc = accessor.docString.kdocToTsDoc(indent = "    ").takeIf(String::isNotBlank)
                                listOfNotNull(
                                    doc, "    ${accessor.simpleName}${sig};"
                                ).joinToString(separator = "\n")
                            }
                        }
                    }
                }
                |}
                |export type ${targetClassName}Type = ${targetClassName};
                |""".trimMargin()
            )
        }
    }

    private fun buildInterfaceHeader(forDeclaration: KSDeclaration, targetClassName: String): String {
        val extend = if (forDeclaration is KSClassDeclaration) {
            forDeclaration.superTypes
                .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
                .singleOrNull {
                    it.classKind == ClassKind.CLASS && it.isAnnotationPresent(LuajExpose::class)
                }
                ?.simpleName?.getShortName()?.takeIf(String::isNotBlank)
                ?.let { " extends $it" }
                ?: ""
        } else ""
        return "export interface $targetClassName$extend"
    }

    private fun functionSignature(
        args: List<Pair<String, String>>,
        returnType: KSTypeReference?,
        separator: String
    ): String {
        val ret = returnType?.let(::tsType).takeUnless { it.isNullOrEmpty() } ?: "void"
        return "(${args.joinToString { (name, type) -> "$name: $type" }})$separator $ret"
    }

    private fun functionPrototypeSignature(decl: KSFunctionDeclaration) = functionSignature(
        args = decl.parameters.mapIndexed { i, arg ->
            (arg.name?.asString() ?: "arg$i") to tsType(arg.type)
        },
        returnType = decl.returnType,
        separator = ":"
    )

    private fun functionTypeSignature(type: KSTypeReference, resolved: KSType) = functionSignature(
        args = buildList {
            val receiver = (type.element as? KSCallableReference)?.receiverType
            if (receiver != null) {
                add("this" to tsType(receiver))
                addAll(functionTypeArgs(resolved.arguments.drop(1)))
            } else addAll(functionTypeArgs(resolved.arguments))
        },
        returnType = resolved.arguments.last().type,
        separator = " =>"
    )

    private fun functionTypeArgs(args: List<KSTypeArgument>) = args.dropLast(1)
        .mapIndexed { i, arg ->
            val argname = arg.getAnnotationsByType(ParameterName::class)
                .singleOrNull()?.name ?: "arg$i"
            argname to tsType(arg.type!!)
        }

    private fun tsTypeSimpleMapping(typeDecl: KSDeclaration): String? = when (typeDecl.simpleName.getShortName()) {
        "String" -> "string"
        "Int", "Double", "Long", "Float", "Short", "Byte" -> "number"
        "Boolean" -> "boolean"
        "Unit" -> "void"
        else -> if (typeDecl is KSClassDeclaration) {
            when (typeDecl.toClassName()) {
                LuaValueClassName -> "any"
                LuaTableClassName -> "Record<string, any>"
                LuaFunctionClassName -> "(...args: any[]) => any"
                else -> null
            }
        } else null
    }

    private fun tsType(type: KSTypeReference): String {
        val resolved = type.resolve()
        tsTypeSimpleMapping(resolved.declaration)?.let { return it }

        return if (resolved.isFunctionType) {
            functionTypeSignature(type, resolved)
        } else {
            val decl = resolved.declaration
            if (decl is KSClassDeclaration && decl.getAllSuperTypes().any { it.toClassName() == KotlinIterableName }) {
                val bound = resolved.arguments.singleOrNull()?.type
                    ?: error("Expected a single argument type", resolved.declaration)
                "${tsType(bound)}[]"
            } else {
                decl.simpleName.getShortName()
            }
        }
    }

    private fun String?.kdocToTsDoc(indent: String = ""): String {
        val text = this?.trim()?.takeIf(String::isNotEmpty) ?: "No documentation provided"
        val lines = text.split(Regex("\r?\n"))
        val body = lines.joinToString("\n") { "$indent * ${it.trim()}" }
        return "$indent/**\n$body\n$indent */"
    }

    private fun OutputStream.appendTs(str: String) = this.write(str.toByteArray())

    private fun error(message: String, at: KSNode): Nothing {
        logger.error(message, at)
        error(message)
    }

    // region Imports collection
    private fun collectImports(forDeclaration: KSDeclaration, properties: Map<String, ExposedData>): Set<Import> {
        val result = mutableSetOf<Import>()

        // extends base type
        baseTypeName(forDeclaration)?.let { result += it to it }

        properties.values.forEach { accessor ->
            when (accessor) {
                is ExposedPropertyLike -> addTypeRefNames(result, accessor.type)
                is ExposedFunction -> {
                    accessor.declaration.parameters.forEach { p -> addTypeRefNames(result, p.type) }
                    accessor.declaration.returnType?.let { addTypeRefNames(result, it) }
                }
            }
        }

        return result
    }

    private fun baseTypeName(forDeclaration: KSDeclaration): String? =
        if (forDeclaration is KSClassDeclaration) {
            forDeclaration.superTypes
                .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
                .singleOrNull { it.classKind == ClassKind.CLASS && it.isAnnotationPresent(LuajExpose::class) }
                ?.simpleName?.getShortName()?.takeIf(String::isNotBlank)
        } else null

    private fun addTypeRefNames(out: MutableSet<Import>, typeRef: KSTypeReference) {
        val type = typeRef.resolve()
        // If primitive mapping exists, no import needed
        if (tsTypeSimpleMapping(type.declaration) != null) return
        if (type.isFunctionType) {
            // function: check arg and return types
            type.arguments.dropLast(1).forEach { arg -> arg.type?.let { addTypeRefNames(out, it) } }
            type.arguments.lastOrNull()?.type?.let { addTypeRefNames(out, it) }
            return
        }
        val decl = type.declaration
        if (decl is KSClassDeclaration && decl.getAllSuperTypes().any { it.toClassName() == KotlinIterableName }) {
            val bound = type.arguments.singleOrNull()?.type ?: return
            addTypeRefNames(out, bound)
            return
        }
        val shortName = decl.simpleName.getShortName()
        val name = typeRef.getAnnotationsByType(LuajMapped::class)
            .ifEmpty { decl.getAnnotationsByType(LuajMapped::class) }
            .map { it.import.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: shortName
        out += shortName to name
    }
    // endregion
}
