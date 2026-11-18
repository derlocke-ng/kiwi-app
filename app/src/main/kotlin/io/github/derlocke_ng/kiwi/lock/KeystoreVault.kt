package io.github.derlocke_ng.kiwi.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import io.github.derlocke_ng.kiwi.core.KiwiException
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
 * Wraps this phone's age key with an RSA key in the Android Keystore. Wrapping uses the public
 * half and asks nothing; opening uses the private half, which never leaves the Keystore and
 * works only for [UNLOCKED_SECONDS] after the screen lock was confirmed.
 */
class KeystoreVault(private val context: Context) : Vault {
    override val ready: Boolean
        get() = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override fun wrap(secret: String): ByteArray = try {
        val store = keystore()
        if (!store.containsAlias(ALIAS)) newKey()
        // as a plain public key: so the cipher is not the Keystore's, which would ask for the screen lock
        val public = store.getCertificate(ALIAS).publicKey
        val plain = KeyFactory.getInstance(public.algorithm).generatePublic(X509EncodedKeySpec(public.encoded))
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, plain, OAEP)
            doFinal(secret.toByteArray())
        }
    } catch (e: GeneralSecurityException) {
        throw KiwiException(Problem.NoScreenLock, e)
    } catch (e: ProviderException) {
        // how some Keystores refuse to make a key without a screen lock
        throw KiwiException(Problem.NoScreenLock, e)
    }

    override fun open(wrapped: ByteArray): String {
        val key = try {
            keystore().getKey(ALIAS, null) as? PrivateKey
        } catch (e: GeneralSecurityException) {
            null
        } ?: throw KiwiException(Problem.KeyLost)
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, OAEP)
                doFinal(wrapped).decodeToString()
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // the screen lock was removed since: the Keystore dropped the key for good
            throw KiwiException(Problem.KeyLost, e)
        } catch (e: GeneralSecurityException) {
            // UserNotAuthenticatedException, or what a Keystore throws in its place
            throw KiwiException(Problem.Locked, e)
        }
    }

    override fun forget() {
        try {
            keystore().deleteEntry(ALIAS)
        } catch (e: GeneralSecurityException) {
            // nothing to delete
        }
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun newKey() {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        UNLOCKED_SECONDS,
                        KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(UNLOCKED_SECONDS)
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
        const val ALIAS = "kiwi-controller"
        const val UNLOCKED_SECONDS = 30
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        // SHA-1 inside MGF1 is what the Keystore does on every Android version; say so on both sides
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
