package io.github.derlocke_ng.kiwi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTest {
    private fun problem(block: () -> Unit): Problem = assertThrows(KiwiException::class.java) { block() }.problem

    @Test
    fun joinsAndLogsIn() {
        FakeControlNode().use { node ->
            val invite = Invite.parse(node.newInvite())
            val key = AgeKey.new()
            val fleet = Controller.fleetOf(invite)
            assertEquals(node.ca.pem, fleet.caPem)
            val me = Controller.join(invite, fleet, "phone", key)
            assertEquals("phone", me.name)
            assertEquals("0123456789abcdef", me.store)
            assertEquals(key.public, node.controllers["phone"])

            val api = Controller.login(me, key)
            val answer = api.call("GET", "/api/fleet/revision")
            assertEquals(200, answer.status)
            assertEquals("rev-for-phone", answer.text("revision"))
        }
    }

    @Test
    fun aTakenNameGetsANumber() {
        FakeControlNode().use { node ->
            node.controllers["phone"] = AgeKey.new().public
            val invite = Invite.parse(node.newInvite())
            val me = Controller.join(invite, Controller.fleetOf(invite), "phone", AgeKey.new())
            assertEquals("phone-2", me.name)
        }
    }

    @Test
    fun anInviteWorksOnce() {
        FakeControlNode().use { node ->
            val invite = Invite.parse(node.newInvite())
            val fleet = Controller.fleetOf(invite)
            Controller.join(invite, fleet, "phone", AgeKey.new())
            assertEquals(Problem.InviteRefused, problem { Controller.join(invite, fleet, "tablet", AgeKey.new()) })
        }
    }

    @Test
    fun sendsNothingToAnotherFleet() {
        // the address answers with another CA than the invite names
        FakeControlNode().use { node ->
            val other = TestCa("someone else")
            val link = node.newInvite().substringBeforeLast('.') + "." + sha256Hex(other.pem.toByteArray()).take(8)
            assertEquals(Problem.WrongFleet, problem { Controller.fleetOf(Invite.parse(link)) })
            assertEquals(listOf("GET /api/ca"), node.requests)
        }
    }

    @Test
    fun doesNotJoinThroughACertificateOfAnotherCa() {
        // the right CA is shown, but the connection is not the fleet's: the key and the code stay here
        val real = TestCa()
        FakeControlNode(ca = real, tlsFrom = TestCa("man in the middle")).use { node ->
            val invite = Invite.parse(node.newInvite())
            val fleet = Controller.fleetOf(invite)
            val e = assertThrows(KiwiException::class.java) { Controller.join(invite, fleet, "phone", AgeKey.new()) }
            assertEquals(Problem.NotTrusted, e.problem)
            assertTrue(e.detail, e.detail!!.contains("Exception"))
            assertFalse(node.requests.contains("POST /api/join"))
            assertTrue(node.controllers.isEmpty())
        }
    }

    @Test
    fun checksTheNameOfTheCertificate() {
        FakeControlNode().use { node ->
            val byAddress = node.url.replace("localhost", "127.0.0.1")
            val e = assertThrows(KiwiException::class.java) { ControlApi(byAddress, node.ca.pem).call("GET", "/api/ca") }
            assertEquals(Problem.NotTrusted, e.problem)
            assertEquals(200, ControlApi(node.url, node.ca.pem).call("GET", "/api/ca").status)
        }
    }

    @Test
    fun anExpiredCertificateIsNotTrusted() {
        val ca = TestCa()
        FakeControlNode(ca = ca, tls = ca.serverContext("localhost", expired = true)).use { node ->
            val e = assertThrows(KiwiException::class.java) { ControlApi(node.url, ca.pem).call("GET", "/api/ca") }
            assertEquals(Problem.NotTrusted, e.problem)
        }
    }

    @Test
    fun aCertificateNotMadeForAServerIsNotTrusted() {
        val ca = TestCa()
        FakeControlNode(ca = ca, tls = ca.serverContext("localhost", forServers = false)).use { node ->
            val e = assertThrows(KiwiException::class.java) { ControlApi(node.url, ca.pem).call("GET", "/api/ca") }
            assertEquals(Problem.NotTrusted, e.problem)
        }
    }

    @Test
    fun aCaThatIsNoCertificateIsNoControlNode() {
        assertEquals(Problem.NotAControlNode, problem { ControlApi("https://localhost:1", "not a certificate") })
        assertEquals(Problem.NotAControlNode, problem { ControlApi("https://localhost:1", "") })
    }

    @Test
    fun aNodeWithoutACaIsNoControlNode() {
        FakeControlNode(shownCa = null).use { node ->
            assertEquals(Problem.NotAControlNode, problem { Controller.fleetOf(Invite.parse(node.newInvite())) })
        }
    }

    @Test
    fun nothingAnswers() {
        val invite = Invite.parse("https://localhost:1/#join=abc.def")
        val e = assertThrows(KiwiException::class.java) { Controller.fleetOf(invite) }
        assertEquals(Problem.NoAnswer, e.problem)
        assertTrue(e.detail, e.detail!!.startsWith("ConnectException"))
    }

    @Test
    fun aNameThePhoneDoesNotFind() {
        // .invalid never resolves (RFC 6761)
        val invite = Invite.parse("https://control.gate.invalid/#join=abc.def")
        val e = assertThrows(KiwiException::class.java) { Controller.fleetOf(invite) }
        assertEquals(Problem.NoName, e.problem)
        assertTrue(e.detail, e.detail!!.contains("control.gate.invalid"))
    }

    @Test
    fun problemsOfTheAppItselfHaveNoDetail() {
        assertEquals(null, KiwiException(Problem.BadName).detail)
    }

    @Test
    fun refusesABadNameBeforeSending() {
        FakeControlNode().use { node ->
            val invite = Invite.parse(node.newInvite())
            val fleet = Controller.fleetOf(invite)
            assertEquals(Problem.BadName, problem { Controller.join(invite, fleet, "My Phone", AgeKey.new()) })
            assertFalse(node.requests.contains("POST /api/join"))
        }
    }

    @Test
    fun aRemovedControllerCannotLogIn() {
        FakeControlNode().use { node ->
            val invite = Invite.parse(node.newInvite())
            val key = AgeKey.new()
            val me = Controller.join(invite, Controller.fleetOf(invite), "phone", key)
            node.controllers.remove("phone")
            assertEquals(Problem.UnknownController, problem { Controller.login(me, key) })
        }
    }

    @Test
    fun theWrongKeyDoesNotLogIn() {
        FakeControlNode().use { node ->
            val invite = Invite.parse(node.newInvite())
            val me = Controller.join(invite, Controller.fleetOf(invite), "phone", AgeKey.new())
            assertEquals(Problem.WrongKey, problem { Controller.login(me, AgeKey.new()) })
        }
    }

    @Test
    fun withoutATokenTheNodeAsksWho() {
        FakeControlNode().use { node ->
            assertEquals(401, ControlApi(node.url, node.ca.pem).call("GET", "/api/fleet/revision").status)
        }
    }
}
