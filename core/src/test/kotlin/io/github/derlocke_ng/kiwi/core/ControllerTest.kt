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
            assertEquals(Problem.NoAnswer, problem { Controller.join(invite, fleet, "phone", AgeKey.new()) })
            assertFalse(node.requests.contains("POST /api/join"))
            assertTrue(node.controllers.isEmpty())
        }
    }

    @Test
    fun checksTheNameOfTheCertificate() {
        FakeControlNode().use { node ->
            val byAddress = node.url.replace("localhost", "127.0.0.1")
            val e = assertThrows(KiwiException::class.java) { ControlApi(byAddress, node.ca.pem).call("GET", "/api/ca") }
            assertEquals(Problem.NoAnswer, e.problem)
            assertEquals(200, ControlApi(node.url, node.ca.pem).call("GET", "/api/ca").status)
        }
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
        assertEquals(Problem.NoAnswer, problem { Controller.fleetOf(invite) })
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
