@file:OptIn(ExperimentalCompilerApi::class)

package io.kshitij.typestring.processor

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

class TypeStringProcessorTest {

    // The processor module has no dependency on typestring-annotations (see
    // TypeStringProcessor's comment on generateTypeStringAnnotation), so test fixtures declare
    // their own copy of the annotation rather than relying on classpath inheritance.
    private val generateTypeStringAnnotationSource = SourceFile.kotlin(
        "GenerateTypeString.kt",
        """
        package io.kshitij.typestring

        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS)
        annotation class GenerateTypeString
        """.trimIndent(),
    )

    @Test
    fun `generates typeString property for flat sealed hierarchy`() {
        val source = SourceFile.kotlin(
            "Result.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            sealed class Result {
                data class Success(val value: String) : Result()
                data class NetworkError(val code: Int) : Result()
                object Loading : Result()
            }
            """.trimIndent(),
        )

        val compilation = KotlinCompilation().apply {
            sources = listOf(source, generateTypeStringAnnotationSource)
            useKsp2()
            symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(TypeStringProcessorProvider())
            inheritClassPath = true
        }

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generated = compilation.kspSourcesDir.walkTopDown()
            .firstOrNull { it.name == "ResultTypeString.kt" }
        assertTrue(generated != null, "expected generated ResultTypeString.kt")
        val text = generated.readText()
        // Success/NetworkError/Loading are nested inside Result, so KotlinPoet qualifies them
        // as Result.Success etc. (matches the real SampleResult pattern from docs/plan_v1.md).
        assertTrue(text.contains("is Result.Success -> \"Success\""), "actual generated text:\n$text")
        assertTrue(text.contains("is Result.NetworkError -> \"NetworkError\""))
        assertTrue(text.contains("is Result.Loading -> \"Loading\""))
    }

    @Test
    fun `nested sealed subclass is treated as a single opaque branch`() {
        val source = SourceFile.kotlin(
            "Nested.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            sealed class Outer {
                object Leaf : Outer()
                sealed class InnerSealed : Outer() {
                    object InnerLeafA : InnerSealed()
                    object InnerLeafB : InnerSealed()
                }
            }
            """.trimIndent(),
        )

        val compilation = KotlinCompilation().apply {
            sources = listOf(source, generateTypeStringAnnotationSource)
            useKsp2()
            symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(TypeStringProcessorProvider())
            inheritClassPath = true
        }

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generated = compilation.kspSourcesDir.walkTopDown()
            .first { it.name == "OuterTypeString.kt" }
            .readText()

        assertTrue(generated.contains("is Outer.Leaf -> \"Leaf\""), "actual generated text:\n$generated")
        assertTrue(generated.contains("is Outer.InnerSealed -> \"InnerSealed\""))
        // getSealedSubclasses() is non-recursive by design (Phase 2 scope):
        // InnerSealed's own children must not appear here.
        assertTrue(!generated.contains("InnerLeafA"))
        assertTrue(!generated.contains("InnerLeafB"))
    }

    @Test
    fun `non-sealed annotated class fails the build with a symbol-naming error`() {
        val source = SourceFile.kotlin(
            "NotSealed.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            class NotSealed
            """.trimIndent(),
        )

        val compilation = KotlinCompilation().apply {
            sources = listOf(source, generateTypeStringAnnotationSource)
            useKsp2()
            symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(TypeStringProcessorProvider())
            inheritClassPath = true
        }

        val result = compilation.compile()
        assertTrue(result.exitCode != KotlinCompilation.ExitCode.OK)
        assertTrue(result.messages.contains("NotSealed"), "actual messages:\n${result.messages}")
    }

    @Test
    fun `empty sealed class compiles with a warning and generates no property`() {
        val source = SourceFile.kotlin(
            "Empty.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            sealed class Empty
            """.trimIndent(),
        )

        val compilation = KotlinCompilation().apply {
            sources = listOf(source, generateTypeStringAnnotationSource)
            useKsp2()
            symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(TypeStringProcessorProvider())
            inheritClassPath = true
        }

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertTrue(result.messages.contains("Empty"), "actual messages:\n${result.messages}")

        val generated = compilation.kspSourcesDir.walkTopDown()
            .firstOrNull { it.name == "EmptyTypeString.kt" }
        assertTrue(generated == null, "expected no EmptyTypeString.kt to be generated")
    }

    @Test
    fun `typeString resolves polymorphically on a base-typed reference`() {
        val source = SourceFile.kotlin(
            "Poly.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            sealed class PolyBase {
                object Leaf : PolyBase()
            }

            fun useIt(any: Any): String {
                val base = any as PolyBase
                return base.typeString
            }
            """.trimIndent(),
        )

        val compilation = KotlinCompilation().apply {
            sources = listOf(source, generateTypeStringAnnotationSource)
            useKsp2()
            symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(TypeStringProcessorProvider())
            inheritClassPath = true
        }

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
    }
}
