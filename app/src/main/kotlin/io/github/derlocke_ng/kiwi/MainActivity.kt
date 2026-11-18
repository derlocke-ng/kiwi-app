package io.github.derlocke_ng.kiwi

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import io.github.derlocke_ng.kiwi.ui.KiwiApp
import io.github.derlocke_ng.kiwi.ui.theme.KiwiTheme

// A FragmentActivity: the screen lock prompt needs one.
class MainActivity : FragmentActivity() {
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
