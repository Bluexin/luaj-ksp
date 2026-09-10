package be.bluexin.luajksp

import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import be.bluexin.luajksp.annotations.LuajLib
import be.bluexin.luajksp.generators.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.validate

class LKSymbolProcessor(
    codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val packagePaths: Map<String, String>
) : SymbolProcessor {

    private val kotlinGen = KotlinAccessGenerator(codeGenerator, logger)
    private val luaGen = LuaTypingGenerator(codeGenerator, logger)
    private val tsGen = TypeScriptTypingGenerator(codeGenerator, logger, packagePaths)
    private val libKotlinGen = LuaLibGenerator(codeGenerator, logger)
    private val libLuaGen = LuaLibLuaTypingGenerator(codeGenerator, logger)
    private val libTsGen = LuaLibTsTypingGenerator(codeGenerator, logger)

    init {
        logger.warn("Package Paths : $packagePaths")
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        return processInternal(resolver) + processExternal(resolver) + processLib(resolver)
    }

    private fun processInternal(resolver: Resolver): List<KSAnnotated> = resolver
        .getSymbolsWithAnnotation(LuajExpose::class.qualifiedName!!)
        .filter { it is KSClassDeclaration }.filter {
            if (it.validate()) {
                it.accept(LKVisitor.Internal(it.expose!!, logger), mutableMapOf())
                    .also { props -> kotlinGen.generate(it as KSDeclaration, props) }
                    .also { props -> luaGen.generate(it as KSDeclaration, props, packagePaths) }
                    .also { props -> tsGen.generate(it as KSDeclaration, props) }
                false
            } else true
        }.toList()

    private fun processExternal(resolver: Resolver): List<KSAnnotated> = resolver
        .getSymbolsWithAnnotation(LuajExposeExternal::class.qualifiedName!!).filterNot {
            if ((it is KSTypeAlias) && it.validate()) {
                it.accept(LKVisitor.External(it.exposeExternal!!, logger), mutableMapOf())
                    .also { props -> kotlinGen.generate(it as KSDeclaration, props) }
                    .also { props -> luaGen.generate(it as KSDeclaration, props, packagePaths) }
                    .also { props -> tsGen.generate(it as KSDeclaration, props) }
                true
            } else false
        }.toList()

    private fun processLib(resolver: Resolver): List<KSAnnotated> = resolver
        .getSymbolsWithAnnotation(LuajLib::class.qualifiedName!!)
        .filter { it is KSClassDeclaration }.filter {
            if (it.validate()) {
                val lib = it.lib!!
                it.accept(LKVisitor.Lib(logger), mutableMapOf())
                    .also { props -> libKotlinGen.generate(it as KSClassDeclaration, lib, props) }
                    .also { props -> luaGen.generate(it as KSClassDeclaration, props, packagePaths) }
                    .also { props -> tsGen.generate(it as KSClassDeclaration, props) }
                    .also { _ -> libLuaGen.generate(it as KSClassDeclaration, lib, packagePaths) }
                    .also { _ -> libTsGen.generate(it as KSClassDeclaration, lib, packagePaths) }
                false
            } else true
        }.toList()

    class Provider : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment) = LKSymbolProcessor(
            environment.codeGenerator, environment.logger,
            packagePaths = environment.options["packagePaths"]?.split(",")
                ?.associate { it.split("=").let { (k, v) -> k to v } } ?: emptyMap()
        )
    }
}