package io.kshitij.project

import io.kshitij.typestring.GenerateTypeString

@GenerateTypeString
sealed class SampleResult {
    data class Success(val data: String) : SampleResult()
    data class NetworkError(val code: Int) : SampleResult()
    object Loading : SampleResult()
}

@GenerateTypeString
sealed interface SampleInterface {

    class TypeA : SampleInterface
    class TypeB : SampleInterface

}

fun SampleInterface.testInterface(): String = this.typeString
