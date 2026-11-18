package io.github.derlocke_ng.kiwi.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import io.github.derlocke_ng.kiwi.core.KiwiException
import io.github.derlocke_ng.kiwi.core.Lock
import io.github.derlocke_ng.kiwi.core.Problem
import io.github.derlocke_ng.kiwi.core.Vault
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * An RSA key in the Android Keystore for every lock. Wrapping uses the public half and asks
 * nothing; opening uses the private half, which never leaves the Keystore:
 * - [Lock.Password]: at any time (the password itself is checked by the app);
 * - [Lock.ScreenLock]: for [UNLOCKED_SECONDS] after the screen lock or a fingerprint or face;
 * - [Lock.Biometric]: for [UNLOCKED_SECONDS] after a fingerprint or face, and never again once
 *   another fingerprint or face is added to the phone.
 */
class KeystoreVault(private val context: Context) : Vault {
    override fun can(lock: Lock): Boolean = when (lock) {
        Lock.Password -> true
        Lock.ScreenLock -> context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        // before Android 11 a key cannot be told to open for a fingerprint but not for the PIN
        Lock.Biometric -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    override fun wrap(lock: Lock, plain: ByteArray): ByteArray = try {
        val store = keystore()
        // a new key for the locks the phone can make void: an old one may be void already
        if (lock != Lock.Password || !store.containsAlias(alias(lock))) newKey(lock)
        // as a plain public key: so the cipher is not the Keystore's, which would ask the owner
        val public = store.getCertificate(alias(lock)).publicKey
        val key = KeyFactory.getInstance(public.algorithm).generatePublic(X509EncodedKeySpec(public.encoded))
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, key, OAEP)
            doFinal(plain)
        }
    } catch (e: GeneralSecurityException) {
        throw KiwiException(cannot(lock), e)
    } catch (e: ProviderException) {
        // how some Keystores refuse to make a key the phone cannot guard
        throw KiwiException(cannot(lock), e)
    }

    override fun open(lock: Lock, wrapped: ByteArray): ByteArray {
        val key = try {
            keystore().getKey(alias(lock), null) as? PrivateKey
        } catch (e: GeneralSecurityException) {
            null
        } ?: throw KiwiException(Problem.KeyLost)
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, OAEP)
                doFinal(wrapped)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // the screen lock was removed, or a fingerprint added: the Keystore dropped the key for good
            throw KiwiException(Problem.KeyLost, e)
        } catch (e: GeneralSecurityException) {
            // UserNotAuthenticatedException, or what a Keystore throws in its place
            throw KiwiException(if (lock == Lock.Password) Problem.KeyLost else Problem.Locked, e)
        } catch (e: ProviderException) {
            throw KiwiException(if (lock == Lock.Password) Problem.KeyLost else Problem.Locked, e)
        }
    }

    override fun forget(lock: Lock) {
        try {
            keystore().deleteEntry(alias(lock))
        } catch (e: GeneralSecurityException) {
            // nothing to delete
        }
    }

    private fun cannot(lock: Lock) = if (lock == Lock.Biometric) Problem.NoBiometric else Problem.NoScreenLock

    private fun alias(lock: Lock) = "kiwi-" + lock.name.lowercase()

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun newKey(lock: Lock) {
        val spec = KeyGenParameterSpec.Builder(alias(lock), KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .apply {
                when {
                    lock == Lock.Password -> Unit
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        setUserAuthenticationRequired(true)
                        setUserAuthenticationParameters(
                            UNLOCKED_SECONDS,
                            if (lock == Lock.Biometric) {
                                KeyProperties.AUTH_BIOMETRIC_STRONG
                            } else {
                                KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
                            },
                        )
                    }
                    lock == Lock.ScreenLock -> {
                        setUserAuthenticationRequired(true)
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(UNLOCKED_SECONDS)
                    }
                    else -> throw KiwiException(Problem.NoBiometric)
                }
            }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val UNLOCKED_SECONDS = 15
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        // SHA-1 inside MGF1 is what the Keystore does on every Android version; say so on both sides
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
