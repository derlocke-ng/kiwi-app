package io.github.derlocke_ng.kiwi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * What guards this phone's key while it rests. On Android it is a Keystore key that works only
 * after the screen lock; [open] throws [Problem.Locked] until then.
 */
interface Vault {
    /** Whether the phone has a screen lock to guard the key with. */
    val ready: Boolean

    fun wrap(secret: String): ByteArray

    fun open(wrapped: ByteArray): String

    fun forget()
}

/**
 * This phone as a controller, as it rests in [file]: the membership and the key, wrapped by
 * [vault]. Every call blocks.
 */
class Phone(private val file: File, private val vault: Vault) {
    /** The fleet this phone joined, or null. */
    fun membership(): Membership? = read()?.first

    /**
     * Join with an invite link. The key is made and wrapped before anything is sent, and kept
     * only once the control node took it.
     */
    fun join(link: String, name: String): Membership {
        val invite = Invite.parse(link)
        if (!isControllerName(name)) throw KiwiException(Problem.BadName)
        if (!vault.ready) throw KiwiException(Problem.NoScreenLock)
        val fleet = Controller.fleetOf(invite)
        val key = AgeKey.new()
        val wrapped = vault.wrap(key.secret)
        val me = Controller.join(invite, fleet, name, key)
        try {
            write(me, wrapped)
        } catch (e: IOException) {
            throw KiwiException(Problem.KeyLost, e)
        }
        return me
    }

    /** Open the key and log in. [Problem.Locked] until the screen lock was confirmed. */
    fun login(): ControlApi {
        val (me, wrapped) = read() ?: throw KiwiException(Problem.KeyLost)
        return Controller.login(me, AgeKey.of(vault.open(wrapped)))
    }

    /** Drop the membership and the key. The control node still lists this phone until it is removed there. */
    fun forget() {
        file.delete()
        vault.forget()
    }

    private fun read(): Pair<Membership, ByteArray>? = try {
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        fun text(key: String) = Answer(0, json).text(key) ?: throw IllegalArgumentException(key)
        Membership(text("url"), text("name"), text("store"), text("ca")) to Base64.getDecoder().decode(text("key"))
    } catch (e: Exception) {
        null
    }

    private fun write(me: Membership, wrapped: ByteArray) {
        val json = jsonOf(
            "url" to me.url, "name" to me.name, "store" to me.store, "ca" to me.caPem,
            "key" to Base64.getEncoder().encodeToString(wrapped),
        )
        val tmp = File(file.path + ".new")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
