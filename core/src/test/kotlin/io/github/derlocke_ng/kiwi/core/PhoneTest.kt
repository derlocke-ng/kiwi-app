package io.github.derlocke_ng.kiwi.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A vault that only turns the bytes around: enough to see that the plain key is never written. */
class FakeVault(val cannot: Set<Lock> = emptySet()) : Vault {
    /** The locks whose owner was not asked yet. */
    val locked = mutableSetOf<Lock>()
    val forgotten = mutableSetOf<Lock>()
    var wraps = 0

    override fun can(lock: Lock) = lock !in cannot

    override fun wrap(lock: Lock, plain: ByteArray): ByteArray {
        wraps++
        forgotten -= lock
        return plain.reversedArray()
    }

    override fun open(lock: Lock, wrapped: ByteArray): ByteArray {
        if (lock in forgotten) throw KiwiException(Problem.KeyLost)
        if (lock in locked) throw KiwiException(Problem.Locked)
        return wrapped.reversedArray()
    }

    override fun forget(lock: Lock) {
        forgotten += lock
    }
}

class PhoneTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun problem(block: () -> Unit): Problem = assertThrows(KiwiException::class.java) { block() }.problem

    private fun file() = File(folder.root, "controller.json")

    private fun revision(me: Membership, key: AgeKey) =
        Controller.login(me, key).call("GET", "/api/fleet/revision").text("revision")

    @Test
    fun joinsKeepsTheKeyWrappedAndOpensItAgain() {
        FakeControlNode().use { node ->
            val vault = FakeVault()
            assertNull(Phone(file(), vault).membership())
            val (me, key) = Phone(file(), vault).join(node.newInvite(), "phone")
            assertEquals("phone", me.name)
            assertFalse(file().readText().contains("AGE-SECRET-KEY"))
            assertFalse(file().readText().contains(key.secret.reversed()))

            // as after a restart: from the file alone
            val again = Phone(file(), vault)
            assertEquals(me, again.membership())
            assertEquals(setOf(Lock.ScreenLock), again.locks())
            assertEquals("rev-for-phone", revision(me, again.open(Lock.ScreenLock)))
        }
    }

    @Test
    fun staysLockedUntilTheOwnerWasAsked() {
        FakeControlNode().use { node ->
            val vault = FakeVault()
            val phone = Phone(file(), vault)
            val (_, key) = phone.join(node.newInvite(), "phone")
            vault.locked += Lock.ScreenLock
            assertEquals(Problem.Locked, problem { phone.open(Lock.ScreenLock) })
            vault.locked.clear()
            assertEquals(key.public, phone.open(Lock.ScreenLock).public)
        }
    }

    @Test
    fun withAPasswordTheKeyRestsUnderIt() {
        FakeControlNode().use { node ->
            // no screen lock on this phone: the password needs none
            val phone = Phone(file(), FakeVault(cannot = setOf(Lock.ScreenLock, Lock.Biometric)))
            val (me, key) = phone.join(node.newInvite(), "phone", "correct horse")
            assertEquals(setOf(Lock.Password), phone.locks())
            assertEquals(key.public, phone.open(Lock.Password, "correct horse").public)
            assertEquals("rev-for-phone", revision(me, phone.open(Lock.Password, "correct horse")))
            assertEquals(Problem.WrongPassword, problem { phone.open(Lock.Password, "correct horsf") })
            assertEquals(Problem.WrongPassword, problem { phone.open(Lock.Password) })
            assertEquals(Problem.KeyLost, problem { phone.open(Lock.ScreenLock) })
        }
    }

    @Test
    fun anyLockThatIsSetOpensIt() {
        FakeControlNode().use { node ->
            val vault = FakeVault()
            val phone = Phone(file(), vault)
            val (_, key) = phone.join(node.newInvite(), "phone")
            phone.set(Lock.Password, key, "correct horse")
            phone.set(Lock.Biometric, key)
            assertEquals(Lock.entries.toSet(), phone.locks())
            for (lock in Lock.entries) assertEquals(key.public, phone.open(lock, "correct horse").public)

            // another password: the old one is of no use
            phone.set(Lock.Password, key, "battery staple")
            assertEquals(Problem.WrongPassword, problem { phone.open(Lock.Password, "correct horse") })
            assertEquals(key.public, phone.open(Lock.Password, "battery staple").public)
        }
    }

    @Test
    fun aLockIsTakenAwayButNotTheLast() {
        FakeControlNode().use { node ->
            val vault = FakeVault()
            val phone = Phone(file(), vault)
            val (_, key) = phone.join(node.newInvite(), "phone")
            assertEquals(Problem.LastLock, problem { phone.remove(Lock.ScreenLock) })
            phone.set(Lock.Password, key, "correct horse")
            phone.remove(Lock.ScreenLock)
            assertEquals(setOf(Lock.Password), phone.locks())
            assertTrue(Lock.ScreenLock in vault.forgotten)
            assertEquals(Problem.KeyLost, problem { phone.open(Lock.ScreenLock) })
            assertEquals(Problem.LastLock, problem { phone.remove(Lock.Password) })
            assertEquals(key.public, phone.open(Lock.Password, "correct horse").public)
        }
    }

    @Test
    fun refusesALockThePhoneCannotGuard() {
        FakeControlNode().use { node ->
            val phone = Phone(file(), FakeVault(cannot = setOf(Lock.Biometric)))
            val (_, key) = phone.join(node.newInvite(), "phone")
            assertEquals(Problem.NoBiometric, problem { phone.set(Lock.Biometric, key) })
            assertEquals(Problem.WeakPassword, problem { phone.set(Lock.Password, key, "short") })
            assertEquals(setOf(Lock.ScreenLock), phone.locks())
        }
    }

    @Test
    fun withoutAScreenLockOrAPasswordNothingIsSent() {
        FakeControlNode().use { node ->
            val phone = Phone(file(), FakeVault(cannot = setOf(Lock.ScreenLock)))
            assertEquals(Problem.NoScreenLock, problem { phone.join(node.newInvite(), "phone") })
            assertEquals(Problem.WeakPassword, problem { phone.join(node.newInvite(), "phone", "short") })
            assertTrue(node.requests.isEmpty())
            assertFalse(file().exists())
        }
    }

    @Test
    fun aRefusedInviteKeepsNothing() {
        FakeControlNode().use { node ->
            val link = node.newInvite()
            Phone(File(folder.root, "other.json"), FakeVault()).join(link, "tablet")
            val phone = Phone(file(), FakeVault())
            assertEquals(Problem.InviteRefused, problem { phone.join(link, "phone") })
            assertNull(phone.membership())
        }
    }

    @Test
    fun checksTheLinkAndTheNameFirst() {
        val vault = FakeVault()
        val phone = Phone(file(), vault)
        assertEquals(Problem.NotAnInvite, problem { phone.join("hello", "phone") })
        assertEquals(Problem.BadName, problem { phone.join("https://localhost:1/#join=abc.def", "My Phone") })
        assertEquals(0, vault.wraps)
    }

    @Test
    fun forgets() {
        FakeControlNode().use { node ->
            val vault = FakeVault()
            val phone = Phone(file(), vault)
            phone.join(node.newInvite(), "phone")
            phone.forget()
            assertNull(phone.membership())
            assertTrue(phone.locks().isEmpty())
            assertEquals(Lock.entries.toSet(), vault.forgotten)
            assertEquals(Problem.KeyLost, problem { phone.open(Lock.ScreenLock) })
        }
    }

    @Test
    fun aBrokenFileIsNoMembership() {
        for (text in listOf("{\"url\":\"https://x.example\"}", "not json",
            "{\"url\":\"u\",\"name\":\"n\",\"store\":\"s\",\"ca\":\"c\",\"locks\":{}}",
            "{\"url\":\"u\",\"name\":\"n\",\"store\":\"s\",\"ca\":\"c\",\"locks\":{\"door\":\"AAAA\"}}")) {
            file().writeText(text)
            assertNull(text, Phone(file(), FakeVault()).membership())
        }
    }
}

class PasswordBoxTest {
    private fun problem(block: () -> Unit): Problem = assertThrows(KiwiException::class.java) { block() }.problem

    @Test
    fun opensWithThePasswordOnly() {
        val secret = "AGE-SECRET-KEY-1".toByteArray() + ByteArray(58) { 'Q'.code.toByte() }
        val box = PasswordBox.seal("correct horse", secret)
        assertArrayEquals(secret, PasswordBox.open("correct horse", box))
        assertEquals(Problem.WrongPassword, problem { PasswordBox.open("Correct horse", box) })
        // what the Keystore's RSA key can wrap in one piece
        assertTrue(box.size <= 190)
    }

    @Test
    fun noTwoBoxesAreAlike() {
        val a = PasswordBox.seal("correct horse", byteArrayOf(1, 2, 3))
        val b = PasswordBox.seal("correct horse", byteArrayOf(1, 2, 3))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun aChangedBoxDoesNotOpen() {
        val box = PasswordBox.seal("correct horse", byteArrayOf(1, 2, 3))
        box[box.size - 1] = (box[box.size - 1] + 1).toByte()
        assertEquals(Problem.WrongPassword, problem { PasswordBox.open("correct horse", box) })
        assertEquals(Problem.KeyLost, problem { PasswordBox.open("correct horse", byteArrayOf(9, 9)) })
    }

    @Test
    fun takesTheSamePasswordHoweverItIsTyped() {
        // é as one character, and as e with an accent after it
        val box = PasswordBox.seal("café au lait", byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), PasswordBox.open("café au lait", box))
    }

    @Test
    fun refusesAShortPassword() {
        assertEquals(Problem.WeakPassword, problem { PasswordBox.seal("1234567", byteArrayOf(1)) })
    }
}
