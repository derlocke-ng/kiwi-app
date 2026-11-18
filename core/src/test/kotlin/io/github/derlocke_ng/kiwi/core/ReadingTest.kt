package io.github.derlocke_ng.kiwi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun readsTheActivity() {
        FakeControlNode().use { node ->
            node.activity = """
                {"jobs": [{"id": "20261118-054012-a1b2c3", "action": "apply", "hosts": ["papaya"], "user": "owner",
                           "state": "done", "started": 1795000000.5, "ended": null, "rc": 0}],
                 "entries": [{"time": "2026-11-18T05:40:12+0100", "user": "phone", "what": "fetched the fleet", "address": "10.9.0.7"},
                             {"time": "2026-11-18T05:39:00+0100", "user": "owner", "what": "logins shown", "hosts": ["papaya"]}]}
            """
            val activity = Reading.activity(loggedIn(node))
            assertEquals(listOf(Job("20261118-054012-a1b2c3", "apply", listOf("papaya"), "owner", "done")), activity.jobs)
            assertEquals("fetched the fleet", activity.entries[0].what)
            assertEquals(listOf("papaya"), activity.entries[1].machines)
        }
    }

    @Test
    fun readsTheLoginsOfAMachine() {
        FakeControlNode().use { node ->
            node.logins = """
                {"host": "papaya", "logins": [
                  {"module": "cloud", "label": "Nextcloud", "address": "https://cloud.papaya.home", "user": "admin",
                   "has_password": true, "password": "correct horse", "note": ""},
                  {"module": "vault", "label": "Vaultwarden", "address": "https://vault.papaya.home", "user": "",
                   "has_password": false, "password": "", "note": "the first account is yours"}]}
            """
            val logins = Reading.logins(loggedIn(node), "papaya")
            assertEquals(Login("Nextcloud", "https://cloud.papaya.home", "admin", "correct horse", ""), logins[0])
            assertNull(logins[1].password)
            assertEquals("the first account is yours", logins[1].note)
        }
    }

    @Test
    fun anOlderControlNodeKeepsItsLoginsAndTheAppStaysLoggedIn() {
        FakeControlNode().use { node ->
            // a token gone stale is that, on a control node that hands logins to a controller
            val api = loggedIn(node).apply { token = "gone" }
            assertEquals(Problem.LoggedOut, problem { Reading.logins(api, "papaya") })
        }
        FakeControlNode(old = true).use { node ->
            assertEquals(Problem.OldControlNode, problem { Reading.logins(loggedIn(node), "papaya") })
        }
        // a name that is none never leaves the phone
        FakeControlNode().use { node ->
            assertEquals(Problem.BadAnswer, problem { Reading.logins(loggedIn(node), "../status") })
            assertFalse(node.requests.any { it.contains("status") })
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
