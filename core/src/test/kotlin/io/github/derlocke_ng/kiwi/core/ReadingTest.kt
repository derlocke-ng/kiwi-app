package io.github.derlocke_ng.kiwi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTest {
    private fun problem(block: () -> Unit): Problem = assertThrows(KiwiException::class.java) { block() }.problem

    /** A controller that joined and logged in. */
    private fun loggedIn(node: FakeControlNode): ControlApi {
        val invite = Invite.parse(node.newInvite())
        val key = AgeKey.new()
        return Controller.login(Controller.join(invite, Controller.fleetOf(invite), "phone", key), key)
    }

    // as `kiwi-server list --porcelain` prints it, shortened
    private val machines = """
        {"fleet": "/fleet/fleet.yaml", "domain": "home", "mesh": "10.8.0.0/16", "groups": {},
         "hosts": [
          {"name": "gate", "hostname": "gate.home", "target": "debian", "role": "master", "phase": "applied",
           "next": "", "here": false, "applied": null, "modules": ["vpn-server", "control"],
           "sites": ["control.gate.home"], "admin_sites": ["control.gate.home"],
           "status": {"script": true}, "updates": {"enabled": true, "days": ["Sun"]}, "errors": [], "warnings": []},
          {"name": "sh3", "hostname": "sh3.home", "target": "ucore", "role": "node", "phase": "install",
           "next": "write the ISO to a stick", "modules": ["cloud", "vault"],
           "sites": ["cloud.sh3.home", "vault.sh3.home"], "admin_sites": [],
           "errors": [], "warnings": ["no backup"]}
         ]}
    """

    // as the control node keeps the last `status --porcelain`
    private val status = """
        {"hosts": [
          {"host": "gate", "target": "core@gate.home", "ok": true, "output": "…", "state": "warn", "locked": false,
           "problems": [{"level": "warn", "text": "a restart is waiting"}], "notes": ["applied yesterday"], "facts": {"x": 1}},
          {"host": "sh3", "ok": false, "state": "down", "problems": [{"level": "bad", "text": "unreachable: timed out"}],
           "notes": [], "facts": {}}
         ], "time": 1795000000.25}
    """

    @Test
    fun readsTheMachines() {
        FakeControlNode().use { node ->
            node.machines = machines
            val network = Reading.network(loggedIn(node))
            assertEquals("home", network.domain)
            assertEquals(listOf("gate", "sh3"), network.machines.map { it.name })
            val gate = network.machines[0]
            assertEquals("master", gate.role)
            assertEquals("debian", gate.target)
            assertEquals(listOf("vpn-server", "control"), gate.modules)
            val sh3 = network.machines[1]
            assertEquals("write the ISO to a stick", sh3.next)
            assertEquals(listOf("no backup"), sh3.warnings)
            assertEquals(
                listOf(
                    Service("control.gate.home", "gate", admin = true),
                    Service("cloud.sh3.home", "sh3", admin = false),
                    Service("vault.sh3.home", "sh3", admin = false),
                ),
                network.services,
            )
        }
    }

    @Test
    fun readsTheLastLook() {
        FakeControlNode().use { node ->
            node.status = status
            val look = Reading.look(loggedIn(node))!!
            assertEquals(1795000000L, look.at)
            assertEquals(State.Warn, look.stateOf("gate"))
            assertEquals(listOf("a restart is waiting"), look.health["gate"]!!.problems)
            assertEquals(listOf("applied yesterday"), look.health["gate"]!!.notes)
            assertEquals(State.Down, look.stateOf("sh3"))
            assertEquals(State.Unknown, look.stateOf("lab1"))
            assertNull(look.error)
        }
    }

    @Test
    fun noLookYet() {
        FakeControlNode().use { node ->
            assertNull(Reading.look(loggedIn(node)))
            assertTrue(Reading.network(loggedIn(node)).machines.isEmpty())
        }
    }

    @Test
    fun aLookThatFailedSaysWhy() {
        FakeControlNode().use { node ->
            node.status = """{"hosts": [], "error": "the fleet file does not load", "time": 5}"""
            val look = Reading.look(loggedIn(node))!!
            assertEquals("the fleet file does not load", look.error)
            assertTrue(look.health.isEmpty())
        }
    }

    @Test
    fun withoutATokenItIsLoggedOut() {
        FakeControlNode().use { node ->
            val api = ControlApi(node.url, node.ca.pem, token = "stale")
            assertEquals(Problem.LoggedOut, problem { Reading.network(api) })
            assertEquals(Problem.LoggedOut, problem { Reading.look(api) })
        }
    }

    @Test
    fun anOlderControlNodeIsToldApart() {
        FakeControlNode(old = true).use { node ->
            assertEquals(Problem.OldControlNode, problem { Reading.network(loggedIn(node)) })
        }
    }

    @Test
    fun aMachineWithoutANameIsNoAnswer() {
        FakeControlNode().use { node ->
            node.machines = """{"hosts": [{"hostname": "x.home"}]}"""
            assertEquals(Problem.BadAnswer, problem { Reading.network(loggedIn(node)) })
        }
    }
}
