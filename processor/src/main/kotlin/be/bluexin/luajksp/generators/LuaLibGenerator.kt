package be.bluexin.luajksp.generators

import be.bluexin.luajksp.*
import be.bluexin.luajksp.annotations.LuajLib
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates a Lua library module file class (as a TwoArgFunction) for classes annotated with [LuajLib].
 *
 * The generated class registers the library under a global table (e.g. `theme`) inside its `call`
 * method, exposing each [be.bluexin.luajksp.annotations.LuajExpose]d function as a Lua callable.
 */
internal class LuaLibGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {
    private val mapping = LuaFunctionMapping(logger)

    fun generate(
        forDeclaration: KSClassDeclaration,
        lib: LuajLib,
        properties: Map<String, ExposedData>
    ) {
        val receiverClassName = forDeclaration.simpleName.asString()
        val targetPackageName = forDeclaration.packageName.asString()
        val libName = lib.name

        val wrappedType = forDeclaration.asStarProjectedType().toTypeName()
        val libClassName = ClassName("${targetPackageName}.lib", "${receiverClassName}Lib")

        logger.info("Generating $libClassName for $receiverClassName", forDeclaration)

        val wrapped = PropertySpec.builder("wrapped", wrappedType)
            .initializer("wrapped")
            .build()

        val functions = properties.values.mapNotNull { it as? ExposedFunction }
        val functionWrappers = mutableMapOf<String, KSType>()

        val call = FunSpec.builder("call")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("modname", LuaValueClassName)
            .addParameter("env", LuaValueClassName)
            .returns(LuaValueClassName)
            .apply {
                addStatement("val lib = %T()", LuaTableClassName)
                functions.forEach {
                    addStatement("lib[%S] = %LWrapper()", it.simpleName, it.simpleName)
                }
                addStatement("env[%S] = lib", libName)
                beginControlFlow("if (!env[%S].isnil())", "package")
                    .addStatement("env[%S][%S][%S] = lib", "package", "loaded", libName)
                    .endControlFlow()
                addStatement("return lib")
            }.build()

        val libClass = TypeSpec.classBuilder(libClassName)
            .addKdoc("Generated with luaj-ksp")
            .superclass(TwoArgFunctionName)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(wrapped.name, wrapped.type)
                    .build()
            )
            .addProperty(wrapped)
            .addFunction(call)
            .apply {
                functions.forEach { mapping.addKtFunctionWrapper(this, it.declaration, wrapped, functionWrappers) }
                functionWrappers.toMap().forEach { (name, type) ->
                    mapping.addFunctionWrapper(this, forDeclaration, name, type, wrapped, functionWrappers.toMap())
                }
            }
            .addOriginatingKSFile(forDeclaration.containingFile!!)
            .build()

        FileSpec.builder(libClassName)
            .indent("    ")
            .addType(libClass)
            .build()
            .writeTo(codeGenerator, true)
    }
}