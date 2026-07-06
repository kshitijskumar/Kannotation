package io.kshitij.typestring.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

class TypeStringProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    // typestring-processor deliberately does NOT depend on typestring-annotations: that module
    // is KMP (android + iOS only, no jvm target) and exposes no variant a plain kotlin("jvm")
    // module can consume. KSP only needs the annotation's fully-qualified name as a string.
    private val generateTypeStringAnnotation = "io.kshitij.typestring.GenerateTypeString"

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val (valid, deferred) = resolver.getSymbolsWithAnnotation(generateTypeStringAnnotation)
            .filterIsInstance<KSClassDeclaration>()
            .partition { it.validate() }

        valid.forEach(::generateTypeStringFile)
        return deferred
    }

    private fun generateTypeStringFile(baseClass: KSClassDeclaration) {
        val subclasses = baseClass.getSealedSubclasses().toList()
        // Phase 3 owns diagnostics for non-sealed/empty bases (logger.error/warn).
        // Phase 2 just skips generation rather than emitting a broken empty `when`.
        if (subclasses.isEmpty()) return

        val baseClassName = baseClass.toClassName()
        val getter = FunSpec.getterBuilder()
            .addCode(
                CodeBlock.builder().apply {
                    beginControlFlow("return when (this)")
                    subclasses.forEach { sub ->
                        addStatement("is %T -> %S", sub.toClassName(), sub.simpleName.asString())
                    }
                    endControlFlow()
                }.build(),
            )
            .build()

        val property = PropertySpec.builder("typeString", STRING)
            .receiver(baseClassName)
            .getter(getter)
            .build()

        FileSpec.builder(baseClassName.packageName, "${baseClassName.simpleName}TypeString")
            .addProperty(property)
            .build()
            .writeTo(codeGenerator, Dependencies(aggregating = false, baseClass.containingFile!!))
    }
}
