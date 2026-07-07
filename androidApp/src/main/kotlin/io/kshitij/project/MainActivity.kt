package io.kshitij.project

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App()
        }

        // Picking the branch off System.nanoTime() (not a compile-time constant) stops R8 from
        // constant-folding the `when` in typeString down to a single reachable branch and
        // stripping the rest as dead code -- that would silently defeat this obfuscation test.
        val results = listOf(
            SampleResult.Success("payload"),
            SampleResult.NetworkError(500),
            SampleResult.Loading,
        )
        val interfaces = listOf(
            SampleInterface.TypeA(),
            SampleInterface.TypeB(),
        )
        val a = results[(System.nanoTime() % results.size).toInt()]
        val b = interfaces[(System.nanoTime() % interfaces.size).toInt()]

        toastA(a)
        toastB(b)
    }


    // ::class.simpleName is a real reflective class-name lookup -- unlike typeString and
    // toString() (both compiler-baked string literals), R8 actually renames what this returns.
    // Logging it side by side is how you tell "obfuscation ran" from "obfuscation ran and our
    // literal survived it".
    fun toastA(a: SampleResult) {
        println("AnnStuff: class type: ${a.typeString} -- reflected: ${a::class.simpleName}")
        Toast.makeText(
            this,
            "AnnStuff: class type: ${a.typeString} -- reflected: ${a::class.simpleName} -- ${a.toString()}",
            Toast.LENGTH_LONG,
        ).show()
    }

    fun toastB(a: SampleInterface) {
        println("AnnStuff: interface type: ${a.typeString} -- reflected: ${a::class.simpleName}")
        Toast.makeText(
            this,
            "AnnStuff: interface type: ${a.typeString} -- reflected: ${a::class.simpleName} -- ${a.toString()}",
            Toast.LENGTH_LONG,
        ).show()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}