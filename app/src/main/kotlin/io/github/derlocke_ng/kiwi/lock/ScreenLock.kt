package io.github.derlocke_ng.kiwi.lock

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Ask for the screen lock (fingerprint or face where the phone has one, else PIN, pattern or
 * password). After a yes the Keystore opens this phone's key for a moment.
 */
fun askScreenLock(activity: FragmentActivity, title: String, subtitle: String, done: (Boolean) -> Unit) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
        },
    )
    // before Android 11 the prompt takes PIN, pattern or password only together with "weak"
    val who = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG else BIOMETRIC_WEAK
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(who or DEVICE_CREDENTIAL)
            .build(),
    )
}
