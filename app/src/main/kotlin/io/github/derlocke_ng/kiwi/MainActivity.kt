package io.github.derlocke_ng.kiwi

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import io.github.derlocke_ng.kiwi.ui.KiwiApp
import io.github.derlocke_ng.kiwi.ui.theme.KiwiTheme

// A FragmentActivity: the fingerprint and screen lock prompt needs one.
class MainActivity : FragmentActivity() {
    private val model: SessionViewModel by viewModels()
    private var leftAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KiwiTheme {
                KiwiApp(model)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        leftAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // out of sight for a while: locked again
        if (leftAt != 0L && SystemClock.elapsedRealtime() - leftAt > LOCK_AFTER_MS) model.lock()
        leftAt = 0L
    }

    private companion object {
        const val LOCK_AFTER_MS = 5 * 60 * 1000L
    }
}
