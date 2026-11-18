package io.github.derlocke_ng.kiwi.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The shortest password taken. */
const val MIN_PASSWORD = 8

/**
 * A secret under a password: Argon2id makes the key, AES-256-GCM seals. The box is
 * `version | salt | nonce | sealed`, 45 bytes longer than the secret.
 */
internal object PasswordBox {
    private const val VERSION: Byte = 1
    private const val SALT = 16
    private const val NONCE = 12
    private const val TAG_BITS = 128

    // version 1: Argon2id, 32 MiB, three passes, one lane
    private const val MEMORY_KIB = 32 * 1024
    private const val PASSES = 3

    fun seal(password: String, secret: ByteArray): ByteArray {
        if (password.length < MIN_PASSWORD) throw KiwiException(Problem.WeakPassword)
        val random = SecureRandom()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce))
            doFinal(secret)
        }
        return byteArrayOf(VERSION) + salt + nonce + sealed
    }

    fun open(password: String, box: ByteArray): ByteArray {
        if (box.size <= 1 + SALT + NONCE || box[0] != VERSION) throw KiwiException(Problem.KeyLost)
        val salt = box.copyOfRange(1, 1 + SALT)
        val nonce = box.copyOfRange(1 + SALT, 1 + SALT + NONCE)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce))
                doFinal(box, 1 + SALT + NONCE, box.size - 1 - SALT - NONCE)
            }
        } catch (e: GeneralSecurityException) {
            throw KiwiException(Problem.WrongPassword, e)
        }
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val out = ByteArray(32)
        val text = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray()
        Argon2BytesGenerator().run {
            init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withMemoryAsKB(MEMORY_KIB)
                    .withIterations(PASSES)
                    .withParallelism(1)
                    .build(),
            )
            generateBytes(text, out)
        }
        text.fill(0)
        return SecretKeySpec(out, "AES")
    }
}
