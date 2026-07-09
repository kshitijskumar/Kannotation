package io.kshitij.project

import io.kshitij.typestring.GenerateTypeString
import io.kshitij.typestring.TypeStringLeaf

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

@GenerateTypeString
sealed class SampleNestedResult {
    object Loading : SampleNestedResult()
    sealed class Failure : SampleNestedResult() {
        object Network : Failure()
        object Unknown : Failure()
    }
    sealed class Success : SampleNestedResult() {
        data class WithData(val payload: String) : Success()
        object Unknown : Success()
    }
}

fun SampleNestedResult.testNested(): String = this.typeString

@GenerateTypeString
sealed class SampleCollapsedResult {
    object DirectLeaf : SampleCollapsedResult()

    @TypeStringLeaf
    sealed class ConfirmPurchaseArgs : SampleCollapsedResult() {
        object FreshWithoutProof : ConfirmPurchaseArgs()
        object Renew : ConfirmPurchaseArgs()
        object PaymentPostVerification : ConfirmPurchaseArgs()
    }
}

fun SampleCollapsedResult.testCollapsed(): String = this.typeString
