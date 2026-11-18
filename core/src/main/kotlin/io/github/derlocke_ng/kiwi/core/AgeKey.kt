package io.github.derlocke_ng.kiwi.core

import kage.Age
import kage.crypto.x25519.X25519Identity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** This phone's age X25519 identity: the secret half opens what the control node sends it. */
class AgeKey private constructor(private val identity: X25519Identity) {
    /** `AGE-SECRET-KEY-1…`: never leaves the phone, and rests only wrapped. */
    val secret: String get() = identity.encodeToString()

    /** `age1…`: what the control node keeps. */
    val public: String get() = identity.recipient().encodeToString()

    fun open(blob: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            Age.decryptStream(listOf(identity), ByteArrayInputStream(blob), out)
        } catch (e: Exception) {
            throw KiwiException(Problem.WrongKey, e)
        }
        return out.toByteArray()
    }

    companion object {
        fun new(): AgeKey = AgeKey(X25519Identity.new())

        fun of(secret: String): AgeKey = try {
            AgeKey(X25519Identity.decode(secret.trim()))
        } catch (e: Exception) {
            throw KiwiException(Problem.WrongKey, e)
        }
    }
}
