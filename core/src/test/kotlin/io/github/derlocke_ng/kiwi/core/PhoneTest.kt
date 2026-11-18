package io.github.derlocke_ng.kiwi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A vault that only turns the text around: enough to see that the plain key is never written. */
class FakeVault(override var ready: Boolean = true, var locked: Boolean = false) : Vault {
    var wraps = 0
    var forgotten = false

    override fun wrap(secret: String): ByteArray {
        wraps++
        return secret.reversed().toByteArray()
    }

    override fun open(wrapped: ByteArray): String {
        if (forgotten) throw KiwiException(Problem.KeyLost)
        if (locked) throw KiwiException(Problem.Locked)
        return wrapped.decodeToString().reversed()
    }

    override fun forget() {
        forgotten = true
    }
}

class PhoneTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun problem(block: () -> Unit): Problem = assertThrows(KiwiException::class.java) { block() }.problem

    private fun file() = File(folder.root, "controller.json")

    @Test
    fun joinsKeepsTheKeyWrappedAndLogsInAgain() {
        FakeControlNode().use { node ->
            val vault = FakeVault()
            assertNull(Phone(file(), vault).membership())
            val me = Phone(file(), vault).join(node.newInvite(), "phone")
            assertEquals("phone", me.name)
            assertFalse(file().readText().contains("AGE-SECRET-KEY"))

            // as after a restart: from the file alone
            val again = Phone(file(), vault)
            assertEquals(me, again.membership())
            assertEquals("rev-for-phone", again.login().call("GET", "/api/fleet/revision").text("revision"))
        }
    }

    @Test
    fun staysLockedUntilTheScreenLock() {
        FakeControlNode().use { node ->
            val vault = FakeVault()
            val phone = Phone(file(), vault)
            phone.join(node.newInvite(), "phone")
            vault.locked = true
            assertEquals(Problem.Locked, problem { phone.login() })
            vault.locked = false
            assertEquals(200, phone.login().call("GET", "/api/fleet/revision").status)
        }
    }

    @Test
    fun withoutAScreenLockNothingIsSent() {
        FakeControlNode().use { node ->
            val phone = Phone(file(), FakeVault(ready = false))
            assertEquals(Problem.NoScreenLock, problem { phone.join(node.newInvite(), "phone") })
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
            assertTrue(vault.forgotten)
            assertEquals(Problem.KeyLost, problem { phone.login() })
        }
    }

    @Test
    fun aBrokenFileIsNoMembership() {
        file().writeText("{\"url\":\"https://x.example\"}")
        assertNull(Phone(file(), FakeVault()).membership())
        file().writeText("not json")
        assertNull(Phone(file(), FakeVault()).membership())
    }
}
