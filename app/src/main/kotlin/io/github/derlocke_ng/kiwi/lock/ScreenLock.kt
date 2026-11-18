package io.github.derlocke_ng.kiwi.lock

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.derlocke_ng.kiwi.core.Lock

/**
 * Ask the owner as [lock] wants it: a fingerprint or face alone for [Lock.Biometric], else the
 * screen lock (fingerprint or face where the phone has one, or PIN, pattern or password).
 * After a yes the Keystore opens that lock's key for a moment.
 */
fun askOwner(
    activity: FragmentActivity,
    lock: Lock,
    title: String,
    subtitle: String,
    cancel: String,
    done: (Boolean) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
    when {
        lock == Lock.Biometric -> info.setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButtonText(cancel)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> info.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        // before Android 11 the prompt takes PIN, pattern or password only together with "weak"
        else -> info.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
    }
    prompt.authenticate(info.build())
}
