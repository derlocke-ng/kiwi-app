package io.github.derlocke_ng.kiwi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.derlocke_ng.kiwi.ui.KiwiApp
import io.github.derlocke_ng.kiwi.ui.theme.KiwiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KiwiTheme {
                KiwiApp()
            }
        }
    }
}
