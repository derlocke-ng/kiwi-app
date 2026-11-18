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

class RegistryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun problem(block: () -> Unit): Problem = assertThrows(KiwiException::class.java) { block() }.problem

    private fun visitor() = Visitor(File(folder.root, "network.json"))

    // as kiwi-server's /api/registry answers, shortened
    private val registry = """
        {"kind": "kiwi-registry", "version": 1, "network": "Kiwi", "domain": "home", "checked": 1795000000.5,
         "status": "https://status.gate.home/", "machine": null, "join": "https://join.gate.home/",
         "play": "https://play.gate.home/", "ca": "https://status.gate.home/api/ca",
         "services": [
           {"label": "Nextcloud", "about": "Files, calendar, contacts", "url": "https://cloud.papaya.home/", "ok": true, "uptime": 99.5, "onion": ""},
           {"label": "Jellyfin", "about": "", "url": "https://media.sorbet.home/", "ok": false, "uptime": null, "onion": "abc.onion"},
           {"label": "Old", "about": "", "url": "http://old.sorbet.home/", "ok": true, "uptime": 100.0, "onion": ""}],
         "games": [
           {"name": "survival", "title": "Minecraft", "address": "play.gate.home:25565", "state": "running"},
           {"name": "creative", "title": "Minecraft", "address": "play.gate.home:25566", "state": "asleep"},
           {"name": "", "title": "nameless", "address": "", "state": "stopped"}]}
    """

    @Test
    fun `the status name gives the registry, and the network is kept`() {
        FakeControlNode().use { node ->
            node.registry = registry
            val visitor = visitor()
            assertNull(visitor.visit())
            val (visit, found) = visitor.find(node.url.removePrefix("https://") + "/status")
            assertEquals(node.url, visit.url)
            assertEquals(node.ca.pem, visit.caPem)
            assertEquals(sha256Hex(node.ca.pem.toByteArray()).take(8), visit.caMark)
            assertEquals(visit, visitor.visit())

            assertEquals("Kiwi", found.network)
            assertEquals("home", found.domain)
            assertEquals(1795000000L, found.checked)
            assertEquals("https://join.gate.home/", found.join)
            // only https pages are offered
            assertEquals(listOf("Nextcloud", "Jellyfin"), found.services.map { it.label })
            assertEquals(Offer("Nextcloud", "Files, calendar, contacts", "https://cloud.papaya.home/", true, 99.5, ""), found.services[0])
            assertEquals("cloud.papaya.home", found.services[0].host)
            assertFalse(found.services[1].ok)
            assertNull(found.services[1].uptime)
            assertEquals(listOf(Running.Yes, Running.Asleep), found.games.map { it.state })
            assertEquals("play.gate.home:25565", found.games[0].address)

            assertEquals(found, visitor.registry(visit))
            visitor.leave()
            assertNull(visitor.visit())
        }
    }

    @Test
    fun `a machine leads to the status name, and only the lead is taken from it`() {
        FakeControlNode().use { status ->
            status.registry = registry
            // the machine: a certificate the status name's CA never signed, as at an address of the LAN
            FakeControlNode(tlsFrom = TestCa("someone else")).use { machine ->
                machine.lead = status.url + "/api/registry?machine=papaya"
                machine.registry = """{"kind": "kiwi-registry", "network": "Not this one"}"""
                val (visit, found) = visitor().find(machine.url)
                assertEquals(status.url, visit.url)
                assertEquals(status.ca.pem, visit.caPem)
                assertEquals("Kiwi", found.network)
                assertEquals(listOf("GET /.well-known/kiwi"), machine.requests)
            }
        }
    }

    @Test
    fun `a lead that is not https is not followed`() {
        FakeControlNode().use { machine ->
            machine.lead = "http://status.gate.home/api/registry"
            val visitor = visitor()
            assertEquals(Problem.NotANetwork, problem { visitor.find(machine.url) })
            assertNull(visitor.visit())
        }
    }

    @Test
    fun `what is no registry is no network`() {
        FakeControlNode().use { node ->
            node.registry = """{"kind": "something else"}"""
            val visitor = visitor()
            assertEquals(Problem.NotANetwork, problem { visitor.find(node.url) })
            assertNull(visitor.visit())
        }
        FakeControlNode(old = true).use { node -> assertEquals(Problem.NotANetwork, problem { visitor().find(node.url) }) }
        FakeControlNode(shownCa = null).use { node -> assertEquals(Problem.NotANetwork, problem { visitor().find(node.url) }) }
        assertEquals(Problem.NotANetwork, problem { visitor().find("not a name") })
        assertEquals(Problem.NotANetwork, problem { visitor().find("") })
    }

    @Test
    fun `after the first time the registry is taken only from the network's CA`() {
        FakeControlNode().use { node ->
            node.registry = registry
            val visit = Visit(node.url, TestCa("someone else").pem)
            assertEquals(Problem.NotTrusted, problem { visitor().registry(visit) })
        }
    }

    @Test
    fun `what is typed may be a name, a link or an address`() {
        assertEquals("papaya.home", hostOf(" Papaya.home "))
        assertEquals("status.gate.home", hostOf("https://status.gate.home/api/registry?machine=x"))
        assertEquals("192.168.2.61", hostOf("192.168.2.61"))
        assertEquals("localhost:8443", hostOf("http://localhost:8443/#x"))
        for (bad in listOf("papaya home", "-x.home", "a@b.home", "https://")) {
            assertEquals(bad, Problem.NotANetwork, problem { hostOf(bad) })
        }
    }

    @Test
    fun `the registry of a control page is at the status name beside it`() {
        assertEquals(listOf("https://status.gate.home", "https://control.gate.home"), registryPlaces("https://control.gate.home/"))
        assertEquals(listOf("https://localhost:8443"), registryPlaces("https://localhost:8443"))
    }

    @Test
    fun `the network's names are those of its domain and those the registry names`() {
        val offer = Offer("Cloud", "", "https://cloud.example.org/", true, null, "")
        val home = Registry("Kiwi", "home", null, listOf(offer), emptyList(), "", "", "https://status.gate.home/")
        assertTrue(home.has("vault.papaya.home"))
        assertTrue(home.has("Cloud.Example.org"))
        assertFalse(home.has("example.org"))
        assertFalse(home.has("home"))
        assertFalse(home.has("nothome"))
        assertFalse(home.copy(domain = "").has("vault.papaya.home"))
        assertEquals(listOf("*.home", "cloud.example.org", "status.gate.home"), home.names)
        assertEquals(listOf("cloud.example.org", "status.gate.home"), home.copy(domain = "").names)
    }

    @Test
    fun `a page's certificate counts when the network's CA signed it for that name`() {
        val ca = TestCa()
        val cert = ca.leaf("cloud.papaya.home")
        assertTrue(isOfFleet(ca.pem, cert, "cloud.papaya.home"))
        assertTrue(isOfFleet(ca.pem, cert, "Cloud.papaya.home."))
        assertFalse(isOfFleet(ca.pem, cert, "vault.papaya.home"))
        assertFalse(isOfFleet(TestCa("someone else").pem, cert, "cloud.papaya.home"))
        assertFalse(isOfFleet(ca.pem, ca.leaf("cloud.papaya.home", expired = true), "cloud.papaya.home"))
        assertFalse(isOfFleet("no certificate", cert, "cloud.papaya.home"))

        val wide = ca.leaf("*.papaya.home")
        assertTrue(isOfFleet(ca.pem, wide, "cloud.papaya.home"))
        assertFalse(isOfFleet(ca.pem, wide, "papaya.home"))
        assertFalse(isOfFleet(ca.pem, wide, "a.cloud.papaya.home"))
    }
}
