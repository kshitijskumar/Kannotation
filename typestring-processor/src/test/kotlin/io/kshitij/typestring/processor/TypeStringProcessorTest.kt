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

    private val typeStringLeafAnnotationSource = SourceFile.kotlin(
        "TypeStringLeaf.kt",
        """
        package io.kshitij.typestring

        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS)
        annotation class TypeStringLeaf
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
    fun `nested sealed subclasses are recursively resolved into dotted paths`() {
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
        assertTrue(generated.contains("is Outer.InnerSealed.InnerLeafA -> \"InnerSealed.InnerLeafA\""))
        assertTrue(generated.contains("is Outer.InnerSealed.InnerLeafB -> \"InnerSealed.InnerLeafB\""))
        // InnerSealed itself must NOT get its own branch now that it's recursed into.
        assertTrue(!generated.contains("is Outer.InnerSealed -> "))
    }

    @Test
    fun `resolves dotted path through three or more levels of sealed nesting`() {
        val source = SourceFile.kotlin(
            "DeepNest.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            sealed class Root {
                sealed class Level1 : Root() {
                    sealed class Level2 : Level1() {
                        object Level3Leaf : Level2()
                    }
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
            .first { it.name == "RootTypeString.kt" }
            .readText()

        assertTrue(
            generated.contains(
                "is Root.Level1.Level2.Level3Leaf -> \"Level1.Level2.Level3Leaf\"",
            ),
            "actual generated text:\n$generated",
        )
    }

    @Test
    fun `exceeding max sealed nesting depth fails the build with a distinct error`() {
        // Nest one level deeper than MAX_SEALED_NESTING_DEPTH (10) to trip the guard.
        val levels = 12
        val body = buildString {
            append("sealed class TooDeep {\n")
            repeat(levels) { i -> append("sealed class L$i : ${if (i == 0) "TooDeep" else "L${i - 1}"}() {\n") }
            append("object Bottom : L${levels - 1}()\n")
            repeat(levels) { append("}\n") }
            append("}\n")
        }
        val source = SourceFile.kotlin(
            "TooDeep.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            $body
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
        assertTrue(result.messages.contains("TooDeep"), "actual messages:\n${result.messages}")
        assertTrue(
            result.messages.contains("max sealed-nesting depth"),
            "actual messages:\n${result.messages}",
        )
    }

    @Test
    fun `mixed-level siblings - leaf and nested sealed at the same level both resolve`() {
        val source = SourceFile.kotlin(
            "Mixed.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            sealed class Mixed {
                object LeafA : Mixed()
                sealed class NestedSealed : Mixed() {
                    object NestedLeafA : NestedSealed()
                    object NestedLeafB : NestedSealed()
                }
                object LeafB : Mixed()
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
            .first { it.name == "MixedTypeString.kt" }
            .readText()

        assertTrue(generated.contains("is Mixed.LeafA -> \"LeafA\""), "actual generated text:\n$generated")
        assertTrue(generated.contains("is Mixed.LeafB -> \"LeafB\""))
        assertTrue(generated.contains("is Mixed.NestedSealed.NestedLeafA -> \"NestedSealed.NestedLeafA\""))
        assertTrue(generated.contains("is Mixed.NestedSealed.NestedLeafB -> \"NestedSealed.NestedLeafB\""))
        assertTrue(!generated.contains("is Mixed.NestedSealed -> "))
    }

    @Test
    fun `non-sealed abstract subclass breaks the chain with a distinct error message`() {
        val source = SourceFile.kotlin(
            "Chain.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            sealed class Chain {
                object DirectLeaf : Chain()
                abstract class AbstractLink : Chain()
            }

            class ConcreteEnd : Chain.AbstractLink()
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
        assertTrue(result.messages.contains("AbstractLink"), "actual messages:\n${result.messages}")
        assertTrue(
            result.messages.contains("breaks the sealed hierarchy chain"),
            "actual messages:\n${result.messages}",
        )
        // Must not be confusable with the root-non-sealed error from the "not sealed" test.
        assertTrue(
            !result.messages.contains("can only be applied to a sealed class or sealed interface"),
            "actual messages:\n${result.messages}",
        )
    }

    @Test
    fun `open concrete subclass is treated as a leaf, not recursed into`() {
        val source = SourceFile.kotlin(
            "OpenChain.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            sealed class Container {
                open class OpenLeaf : Container()
                object OtherLeaf : Container()
            }

            class SubOfOpenLeaf : Container.OpenLeaf()
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
            .first { it.name == "ContainerTypeString.kt" }
            .readText()

        assertTrue(generated.contains("is Container.OpenLeaf -> \"OpenLeaf\""), "actual generated text:\n$generated")
        assertTrue(generated.contains("is Container.OtherLeaf -> \"OtherLeaf\""))
        assertTrue(!generated.contains("SubOfOpenLeaf"))
    }

    @Test
    fun `empty sealed node is treated as a plain leaf`() {
        val source = SourceFile.kotlin(
            "Wrapper.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString

            @GenerateTypeString
            sealed class Wrapper {
                object Direct : Wrapper()
                sealed class EmptyInner : Wrapper()
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
            .first { it.name == "WrapperTypeString.kt" }
            .readText()

        assertTrue(generated.contains("is Wrapper.Direct -> \"Direct\""), "actual generated text:\n$generated")
        assertTrue(generated.contains("is Wrapper.EmptyInner -> \"EmptyInner\""))
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

    @Test
    fun `TypeStringLeaf on a sealed node with children collapses the whole subtree`() {
        val source = SourceFile.kotlin(
            "Collapsed.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString
            import io.kshitij.typestring.TypeStringLeaf

            @GenerateTypeString
            sealed class Args {
                object DirectLeaf : Args()
                @TypeStringLeaf
                sealed class ConfirmPurchaseArgs : Args() {
                    object FreshWithoutProof : ConfirmPurchaseArgs()
                    object Renew : ConfirmPurchaseArgs()
                    sealed class PaymentPostVerification : ConfirmPurchaseArgs() {
                        object Variant : PaymentPostVerification()
                    }
                }
            }
            """.trimIndent(),
        )

        val compilation = KotlinCompilation().apply {
            sources = listOf(source, generateTypeStringAnnotationSource, typeStringLeafAnnotationSource)
            useKsp2()
            symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(TypeStringProcessorProvider())
            inheritClassPath = true
        }

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generated = compilation.kspSourcesDir.walkTopDown()
            .first { it.name == "ArgsTypeString.kt" }
            .readText()

        assertTrue(generated.contains("is Args.DirectLeaf -> \"DirectLeaf\""), "actual generated text:\n$generated")
        assertTrue(generated.contains("is Args.ConfirmPurchaseArgs -> \"ConfirmPurchaseArgs\""))
        assertTrue(!generated.contains("FreshWithoutProof"))
        assertTrue(!generated.contains("Renew"))
        assertTrue(!generated.contains("PaymentPostVerification"))
        assertTrue(!generated.contains("Variant"))
    }

    @Test
    fun `TypeStringLeaf on a non-sealed abstract node rescues the chain-break error`() {
        val source = SourceFile.kotlin(
            "RescuedChain.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString
            import io.kshitij.typestring.TypeStringLeaf

            @GenerateTypeString
            sealed class Chain {
                object DirectLeaf : Chain()
                @TypeStringLeaf
                abstract class AbstractLink : Chain()
            }

            class ConcreteEnd : Chain.AbstractLink()
            """.trimIndent(),
        )

        val compilation = KotlinCompilation().apply {
            sources = listOf(source, generateTypeStringAnnotationSource, typeStringLeafAnnotationSource)
            useKsp2()
            symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(TypeStringProcessorProvider())
            inheritClassPath = true
        }

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertTrue(
            !result.messages.contains("breaks the sealed hierarchy chain"),
            "actual messages:\n${result.messages}",
        )

        val generated = compilation.kspSourcesDir.walkTopDown()
            .first { it.name == "ChainTypeString.kt" }
            .readText()

        assertTrue(generated.contains("is Chain.DirectLeaf -> \"DirectLeaf\""), "actual generated text:\n$generated")
        assertTrue(generated.contains("is Chain.AbstractLink -> \"AbstractLink\""))
        assertTrue(!generated.contains("ConcreteEnd"))
    }

    @Test
    fun `TypeStringLeaf at a deep level yields the full accumulated dotted path`() {
        val source = SourceFile.kotlin(
            "DeepCollapse.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString
            import io.kshitij.typestring.TypeStringLeaf

            @GenerateTypeString
            sealed class Root {
                sealed class Outer : Root() {
                    @TypeStringLeaf
                    sealed class ConfirmPurchaseArgs : Outer() {
                        object FreshWithoutProof : ConfirmPurchaseArgs()
                    }
                }
            }
            """.trimIndent(),
        )

        val compilation = KotlinCompilation().apply {
            sources = listOf(source, generateTypeStringAnnotationSource, typeStringLeafAnnotationSource)
            useKsp2()
            symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(TypeStringProcessorProvider())
            inheritClassPath = true
        }

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generated = compilation.kspSourcesDir.walkTopDown()
            .first { it.name == "RootTypeString.kt" }
            .readText()

        assertTrue(
            generated.contains("is Root.Outer.ConfirmPurchaseArgs -> \"Outer.ConfirmPurchaseArgs\""),
            "actual generated text:\n$generated",
        )
        assertTrue(!generated.contains("FreshWithoutProof"))
    }

    @Test
    fun `TypeStringLeaf collapsed sibling coexists with a normally-recursed sealed sibling`() {
        val source = SourceFile.kotlin(
            "MixedCollapse.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString
            import io.kshitij.typestring.TypeStringLeaf

            @GenerateTypeString
            sealed class Mixed {
                @TypeStringLeaf
                sealed class Collapsed : Mixed() {
                    object A : Collapsed()
                    object B : Collapsed()
                }
                sealed class Recursed : Mixed() {
                    object C : Recursed()
                    object D : Recursed()
                }
            }
            """.trimIndent(),
        )

        val compilation = KotlinCompilation().apply {
            sources = listOf(source, generateTypeStringAnnotationSource, typeStringLeafAnnotationSource)
            useKsp2()
            symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(TypeStringProcessorProvider())
            inheritClassPath = true
        }

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generated = compilation.kspSourcesDir.walkTopDown()
            .first { it.name == "MixedTypeString.kt" }
            .readText()

        assertTrue(generated.contains("is Mixed.Collapsed -> \"Collapsed\""), "actual generated text:\n$generated")
        assertTrue(!generated.contains("Mixed.Collapsed.A"))
        assertTrue(!generated.contains("Mixed.Collapsed.B"))
        assertTrue(generated.contains("is Mixed.Recursed.C -> \"Recursed.C\""))
        assertTrue(generated.contains("is Mixed.Recursed.D -> \"Recursed.D\""))
    }

    @Test
    fun `TypeStringLeaf makes an otherwise-invalid non-sealed abstract descendant irrelevant`() {
        val source = SourceFile.kotlin(
            "IrrelevantDescendant.kt",
            """
            package com.example
            import io.kshitij.typestring.GenerateTypeString
            import io.kshitij.typestring.TypeStringLeaf

            @GenerateTypeString
            sealed class Root {
                object DirectLeaf : Root()
                @TypeStringLeaf
                sealed class Collapsed : Root() {
                    abstract class InvalidNonSealedAbstract : Collapsed()
                }
            }

            class ConcreteEnd : Root.Collapsed.InvalidNonSealedAbstract()
            """.trimIndent(),
        )

        val compilation = KotlinCompilation().apply {
            sources = listOf(source, generateTypeStringAnnotationSource, typeStringLeafAnnotationSource)
            useKsp2()
            symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(TypeStringProcessorProvider())
            inheritClassPath = true
        }

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertTrue(
            !result.messages.contains("breaks the sealed hierarchy chain"),
            "actual messages:\n${result.messages}",
        )

        val generated = compilation.kspSourcesDir.walkTopDown()
            .first { it.name == "RootTypeString.kt" }
            .readText()

        assertTrue(generated.contains("is Root.DirectLeaf -> \"DirectLeaf\""), "actual generated text:\n$generated")
        assertTrue(generated.contains("is Root.Collapsed -> \"Collapsed\""))
        assertTrue(!generated.contains("InvalidNonSealedAbstract"))
    }
}
