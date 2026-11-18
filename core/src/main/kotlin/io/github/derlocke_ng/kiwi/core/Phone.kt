package io.github.derlocke_ng.kiwi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** A way to open this phone's key. The owner chooses which are set; any one of them opens it. */
enum class Lock {
    /** A password of the owner's, asked for by the app. */
    Password,

    /** Fingerprint or face alone: the phone's PIN does not open it. */
    Biometric,

    /** The phone's screen lock: PIN, pattern or password, or fingerprint or face. */
    ScreenLock,
}

/**
 * What guards the key on the phone, one key of its own per [Lock]. On Android these are
 * Keystore keys that never leave the phone:
 * - [Lock.Password]: works at any time. It only ties the password's box to this phone, so the
 *   password cannot be guessed at on a copy of the file.
 * - [Lock.Biometric], [Lock.ScreenLock]: work only shortly after the owner was asked;
 *   [open] throws [Problem.Locked] until then.
 */
interface Vault {
    /** Whether the phone can guard a key this way now. */
    fun can(lock: Lock): Boolean

    fun wrap(lock: Lock, plain: ByteArray): ByteArray

    fun open(lock: Lock, wrapped: ByteArray): ByteArray

    fun forget(lock: Lock)
}

/**
 * This phone as a controller, as it rests in [file]: the membership and the key, wrapped once
 * for every lock that is set. Every call blocks.
 */
class Phone(private val file: File, private val vault: Vault, private val lookup: Lookup = Lookup()) {
    private class Kept(val membership: Membership, val locks: Map<Lock, ByteArray>)

    /** The fleet this phone joined, or null. */
    fun membership(): Membership? = read()?.membership

    /** The ways to unlock that are set. */
    fun locks(): Set<Lock> = read()?.locks?.keys.orEmpty()

    /**
     * Join with an invite link. With a [password] the key rests under it, without one under the
     * screen lock. The key is made and wrapped before anything is sent, and kept only once the
     * control node took it.
     */
    fun join(link: String, name: String, password: String? = null): Pair<Membership, AgeKey> {
        val invite = Invite.parse(link)
        if (!isControllerName(name)) throw KiwiException(Problem.BadName)
        val lock = if (password.isNullOrEmpty()) Lock.ScreenLock else Lock.Password
        check(lock, password)
        val fleet = Controller.fleetOf(invite, lookup)
        val key = AgeKey.new()
        val wrapped = wrap(lock, key, password)
        val me = Controller.join(invite, fleet, name, key, lookup)
        write(Kept(me, mapOf(lock to wrapped)))
        return me to key
    }

    /**
     * Open the key by one of its locks. [Problem.Locked] until the owner was asked, for the
     * locks that ask; [Problem.WrongPassword] for the one that does not.
     */
    fun open(lock: Lock, password: String? = null): AgeKey {
        val wrapped = read()?.locks?.get(lock) ?: throw KiwiException(Problem.KeyLost)
        val plain = vault.open(lock, wrapped)
        val secret = if (lock == Lock.Password) PasswordBox.open(password.orEmpty(), plain) else plain
        return AgeKey.of(secret.decodeToString())
    }

    /** Set a lock, or set it anew (another password). [key] is the opened key. */
    fun set(lock: Lock, key: AgeKey, password: String? = null) {
        val kept = read() ?: throw KiwiException(Problem.KeyLost)
        check(lock, password)
        write(Kept(kept.membership, kept.locks + (lock to wrap(lock, key, password))))
    }

    /** Take a lock away. The last one stays. */
    fun remove(lock: Lock) {
        val kept = read() ?: return
        if (lock !in kept.locks) return
        if (kept.locks.size == 1) throw KiwiException(Problem.LastLock)
        write(Kept(kept.membership, kept.locks - lock))
        vault.forget(lock)
    }

    /** Log in with the opened key. */
    fun login(membership: Membership, key: AgeKey): ControlApi = Controller.login(membership, key, lookup)

    /** Drop the membership and the key. The control node still lists this phone until it is removed there. */
    fun forget() {
        file.delete()
        Lock.entries.forEach(vault::forget)
    }

    private fun check(lock: Lock, password: String?) {
        if (lock == Lock.Password && (password?.length ?: 0) < MIN_PASSWORD) throw KiwiException(Problem.WeakPassword)
        if (!vault.can(lock)) {
            throw KiwiException(if (lock == Lock.Biometric) Problem.NoBiometric else Problem.NoScreenLock)
        }
    }

    private fun wrap(lock: Lock, key: AgeKey, password: String?): ByteArray {
        val secret = key.secret.toByteArray()
        return vault.wrap(lock, if (lock == Lock.Password) PasswordBox.seal(password.orEmpty(), secret) else secret)
    }

    private fun read(): Kept? = try {
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        fun text(key: String) = Answer(0, json).text(key) ?: throw IllegalArgumentException(key)
        val locks = (json["locks"] as JsonObject).entries.associate { (name, wrapped) ->
            Lock.entries.first { it.name.lowercase() == name } to Base64.getDecoder().decode((wrapped as JsonPrimitive).content)
        }
        require(locks.isNotEmpty())
        Kept(Membership(text("url"), text("name"), text("store"), text("ca")), locks)
    } catch (e: Exception) {
        null
    }

    private fun write(kept: Kept) {
        val me = kept.membership
        val locks = kept.locks.entries.associate { (lock, wrapped) ->
            lock.name.lowercase() to JsonPrimitive(Base64.getEncoder().encodeToString(wrapped))
        }
        val json = JsonObject(jsonOf("url" to me.url, "name" to me.name, "store" to me.store, "ca" to me.caPem) + ("locks" to JsonObject(locks)))
        try {
            val tmp = File(file.path + ".new")
            tmp.writeText(json.toString())
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw KiwiException(Problem.KeyLost, e)
        }
    }
}
