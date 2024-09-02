package be.bluexin.luajksp

import be.bluexin.luajksp.annotations.LuajExpose
import be.bluexin.luajksp.annotations.LuajExposeExternal
import be.bluexin.luajksp.generators.KotlinAccessGenerator
import be.bluexin.luajksp.generators.LuaTypingGenerator
import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

class LKSymbolProcessor(
    codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private val kotlinGen = KotlinAccessGenerator(codeGenerator, logger)
    private val luaGen = LuaTypingGenerator(codeGenerator, logger)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        return processInternal(resolver) + processExternal(resolver)
    }

    private fun processInternal(resolver: Resolver): List<KSAnnotated> = resolver
        .getSymbolsWithAnnotation(LuajExpose::class.qualifiedName!!).filterNot {
            if ((it is KSClassDeclaration) && it.validate()) {
                it.accept(LKVisitor.Internal(it.expose!!, logger), mutableMapOf())
                    .also { props -> kotlinGen.generate(it as KSDeclaration, props) }
                    .also { props -> luaGen.generate(it as KSDeclaration, props) }
                true
            } else false
        }.toList()

    private fun processExternal(resolver: Resolver): List<KSAnnotated> = resolver
        .getSymbolsWithAnnotation(LuajExposeExternal::class.qualifiedName!!).filterNot {
            if ((it is KSTypeAlias) && it.validate()) {
                it.accept(LKVisitor.External(it.exposeExternal!!, logger), mutableMapOf())
                    .also { props -> kotlinGen.generate(it as KSDeclaration, props) }
                    .also { props -> luaGen.generate(it as KSDeclaration, props) }
                true
            } else false
        }.toList()

    class Provider : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment) = LKSymbolProcessor(
            environment.codeGenerator, environment.logger
        )
    }
}