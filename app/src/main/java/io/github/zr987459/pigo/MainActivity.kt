package io.github.zr987459.pigo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.zr987459.pigo.core.ui.PiGoTheme
import io.github.zr987459.pigo.nav.PiGoNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as PiGoApp).graph
        setContent {
            PiGoTheme {
                PiGoNavHost(graph)
            }
        }
    }
}
