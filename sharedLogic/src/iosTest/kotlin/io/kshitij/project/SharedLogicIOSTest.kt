package io.kshitij.project

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicIOSTest {

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
    }

    @Test
    fun sampleResultTypeStringResolvesForEachSubclassAndPolymorphically() {
        assertEquals("Success", SampleResult.Success("payload").typeString)
        assertEquals("NetworkError", SampleResult.NetworkError(404).typeString)
        assertEquals("Loading", SampleResult.Loading.typeString)

        val base: SampleResult = SampleResult.Loading
        assertEquals("Loading", base.typeString)
    }
}