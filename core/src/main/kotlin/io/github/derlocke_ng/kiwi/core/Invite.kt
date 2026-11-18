package io.github.derlocke_ng.kiwi.core

import java.security.MessageDigest

/**
 * An invite link from "Add a computer": `https://control.<host>.<domain>/#join=<code>`.
 * The code's last part names the fleet's CA, so the fleet is checked before anything is sent.
 */
data class Invite(val url: String, val code: String) {
    /** The first eight hex digits of the SHA-256 of the CA's PEM text. */
    val caMark: String get() = code.substringAfterLast('.')

    fun isFrom(caPem: String): Boolean = caMark == sha256Hex(caPem.toByteArray()).take(8)

    companion object {
        fun parse(link: String): Invite {
            val text = link.trim()
            val at = text.indexOf("#join=")
            if (at < 0) throw KiwiException(Problem.NotAnInvite)
            val url = text.substring(0, at).trimEnd('/')
            val code = text.substring(at + 6).trim()
            // https only: the phone sends its key and the invite's code there
            if (!url.startsWith("https://") || url.length == 8 || !code.contains('.') || code.any { it.isWhitespace() }) {
                throw KiwiException(Problem.NotAnInvite)
            }
            return Invite(url, code)
        }
    }
}

/** A controller's name as the control node takes it. */
fun isControllerName(name: String): Boolean = Regex("[a-z0-9][a-z0-9-]{0,27}").matches(name)

/** A name the control node takes, made from any text (a phone's model, say). */
fun controllerNameFrom(text: String, fallback: String = "phone"): String {
    val name = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(28).trimEnd('-')
    return if (isControllerName(name)) name else fallback
}

internal fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
