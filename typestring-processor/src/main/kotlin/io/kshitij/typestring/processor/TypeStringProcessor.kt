package io.kshitij.typestring.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Modifier
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

    /** One `when` branch: the leaf class itself and its dotted path (e.g. "Success" or "Failure.Unknown"). */
    private data class TypeStringLeaf(val declaration: KSClassDeclaration, val path: String)

    private companion object {
        // Sealed hierarchies can't cycle (Kotlin's `permits` graph is a compiler-enforced DAG) -
        // this bounds accidental/pathologically deep nesting, not a real cycle.
        const val MAX_SEALED_NESTING_DEPTH = 10
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val (valid, deferred) = resolver.getSymbolsWithAnnotation(generateTypeStringAnnotation)
            .filterIsInstance<KSClassDeclaration>()
            .partition { it.validate() }

        valid.forEach(::generateTypeStringFile)
        return deferred
    }

    private fun generateTypeStringFile(baseClass: KSClassDeclaration) {
        if (Modifier.SEALED !in baseClass.modifiers) {
            logger.error(
                "@GenerateTypeString can only be applied to a sealed class or sealed interface, " +
                    "but '${baseClass.simpleName.asString()}' is not sealed.",
                baseClass,
            )
            return
        }

        val directSubclasses = baseClass.getSealedSubclasses().toList()
        if (directSubclasses.isEmpty()) {
            logger.warn(
                "@GenerateTypeString on '${baseClass.simpleName.asString()}' has no direct " +
                    "subclasses; skipping typeString generation.",
                baseClass,
            )
            return
        }

        val visitedFiles = linkedSetOf<KSFile>()
        baseClass.containingFile?.let(visitedFiles::add)

        val leaves = mutableListOf<TypeStringLeaf>()
        val completed = collectLeaves(
            baseClass = baseClass,
            directSubclasses = directSubclasses,
            ancestorPath = emptyList(),
            depth = 1,
            visitedFiles = visitedFiles,
            leaves = leaves,
        )
        if (!completed) return

        val baseClassName = baseClass.toClassName()
        val getter = FunSpec.getterBuilder()
            .addCode(
                CodeBlock.builder().apply {
                    beginControlFlow("return when (this)")
                    leaves.forEach { leaf ->
                        addStatement("is %T -> %S", leaf.declaration.toClassName(), leaf.path)
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
            .writeTo(codeGenerator, Dependencies(aggregating = false, *visitedFiles.toTypedArray()))
    }

    /**
     * Walks [directSubclasses] (the sealed subclasses of the node currently being expanded),
     * classifying each into a leaf, a further sealed node to recurse into, or a non-sealed
     * abstract chain-break, appending resolved leaves to [leaves] and every visited file to
     * [visitedFiles] along the way.
     *
     * @return false if a fatal error was logged and [generateTypeStringFile] must bail without
     *   writing any file (no partial emission).
     */
    private fun collectLeaves(
        baseClass: KSClassDeclaration,
        directSubclasses: List<KSClassDeclaration>,
        ancestorPath: List<String>,
        depth: Int,
        visitedFiles: MutableSet<KSFile>,
        leaves: MutableList<TypeStringLeaf>,
    ): Boolean {
        if (depth > MAX_SEALED_NESTING_DEPTH) {
            logger.error(
                "@GenerateTypeString on '${baseClass.simpleName.asString()}' exceeded the max " +
                    "sealed-nesting depth of $MAX_SEALED_NESTING_DEPTH while traversing " +
                    "'${ancestorPath.joinToString(".")}'; skipping typeString generation " +
                    "(check for accidental or excessively deep sealed nesting).",
                baseClass,
            )
            return false
        }

        for (sub in directSubclasses) {
            sub.containingFile?.let(visitedFiles::add)
            val path = ancestorPath + sub.simpleName.asString()

            when {
                Modifier.SEALED in sub.modifiers -> {
                    val grandchildren = sub.getSealedSubclasses().toList()
                    if (grandchildren.isEmpty()) {
                        // A sealed node with no children can never itself be instantiated
                        // further, so treat it exactly like a normal leaf.
                        leaves += TypeStringLeaf(sub, path.joinToString("."))
                    } else {
                        val ok = collectLeaves(
                            baseClass, grandchildren, path, depth + 1, visitedFiles, leaves,
                        )
                        if (!ok) return false
                    }
                }

                Modifier.ABSTRACT in sub.modifiers -> {
                    // Distinct wording from the root "is not sealed" error above so the two
                    // failure modes are unambiguous in build output.
                    logger.error(
                        "@GenerateTypeString on '${baseClass.simpleName.asString()}': " +
                            "'${path.joinToString(".")}' is a non-sealed abstract class, which " +
                            "breaks the sealed hierarchy chain - getSealedSubclasses() cannot " +
                            "see subclasses beyond it. Make it sealed, or restructure so every " +
                            "node between the base and the leaves is sealed.",
                        sub,
                    )
                    return false
                }

                else -> {
                    // Concrete leaf: data class / object / final class / open class. An `open`
                    // (non-abstract) class is deliberately NOT treated as a chain-break - its
                    // own further subclasses, if any, are simply invisible to us and irrelevant.
                    leaves += TypeStringLeaf(sub, path.joinToString("."))
                }
            }
        }
        return true
    }
}
