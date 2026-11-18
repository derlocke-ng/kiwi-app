package io.github.derlocke_ng.kiwi

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import io.github.derlocke_ng.kiwi.ui.KiwiApp
import io.github.derlocke_ng.kiwi.ui.theme.KiwiTheme
import java.io.File

// A FragmentActivity: the fingerprint and screen lock prompt needs one.
class MainActivity : FragmentActivity() {
    private val model: SessionViewModel by viewModels()
    private var leftAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        keepCrashes(File(filesDir, CRASH_FILE))
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

    companion object {
        /** In the app's own files: what the app said when it last stopped by itself. */
        const val CRASH_FILE = "crash.txt"
        private const val LOCK_AFTER_MS = 5 * 60 * 1000L
        private var keeping = false

        /** Before the app stops over an error, write the error down: the next start shows it. */
        private fun keepCrashes(file: File) {
            if (keeping) return
            keeping = true
            val before = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { file.writeText(Log.getStackTraceString(error).take(6000)) }
                before?.uncaughtException(thread, error)
            }
        }
    }
}
