package io.github.derlocke_ng.kiwi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.concurrent.thread

/** A DNS server for one test: answers every question with [answer] made from the question. */
class FakeDns(private val answer: (question: ByteArray) -> ByteArray?) : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    val port: Int get() = socket.localPort
    val address: InetAddress = InetAddress.getLoopbackAddress()
    var questions = 0

    init {
        thread(isDaemon = true) {
            try {
                while (true) {
                    val packet = DatagramPacket(ByteArray(1500), 1500)
                    socket.receive(packet)
                    questions++
                    val reply = answer(packet.data.copyOf(packet.length)) ?: continue
                    socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                }
            } catch (e: Exception) {
                // closed
            }
        }
    }

    override fun close() = socket.close()

    companion object {
        /** An answer as a resolver sends it: the question back, then the records, their names as pointers to the question's. */
        fun reply(question: ByteArray, vararg records: Pair<Int, ByteArray>, code: Int = 0): ByteArray {
            val head = question.copyOf()
            head[2] = 0x81.toByte()
            head[3] = (0x80 or code).toByte()
            head[7] = records.size.toByte()
            var out = head
            for ((type, value) in records) {
                out += byteArrayOf(0xc0.toByte(), 12, 0, type.toByte(), 0, 1, 0, 0, 0, 60, 0, value.size.toByte()) + value
            }
            return out
        }

        const val A = 1
        const val CNAME = 5
    }
}

class LookupTest {
    @Test
    fun readsTheAddressesOfAnAnswer() {
        FakeDns { q -> FakeDns.reply(q, FakeDns.A to byteArrayOf(192.toByte(), 168.toByte(), 2, 30)) }.use { dns ->
            val found = DnsQuery.addresses("control.papaya.home", dns.address, dns.port)
            assertEquals(listOf("192.168.2.30"), found.map { it.hostAddress })
        }
    }

    @Test
    fun readsPastAnAlias() {
        // control is another name of papaya, whose address follows
        val alias = byteArrayOf(6) + "papaya".toByteArray() + byteArrayOf(0xc0.toByte(), 12)
        FakeDns { q -> FakeDns.reply(q, FakeDns.CNAME to alias, FakeDns.A to byteArrayOf(10, 9, 0, 2)) }.use { dns ->
            assertEquals(listOf("10.9.0.2"), DnsQuery.addresses("control.papaya.home", dns.address, dns.port).map { it.hostAddress })
        }
    }

    @Test
    fun aNameTheServerDoesNotKnowHasNoAddress() {
        FakeDns { q -> FakeDns.reply(q, code = 3) }.use { dns ->
            assertTrue(DnsQuery.addresses("control.papaya.home", dns.address, dns.port).isEmpty())
        }
    }

    @Test
    fun aBlankOrBrokenAnswerIsNoAddress() {
        FakeDns { q -> FakeDns.reply(q, FakeDns.A to byteArrayOf(0, 0, 0, 0)) }.use { dns ->
            assertTrue(DnsQuery.addresses("control.papaya.home", dns.address, dns.port).isEmpty())
        }
        // an answer to another question, and one cut short
        assertTrue(DnsQuery.answers(7, "x.home", FakeDns.reply(DnsQuery.question(8, "x.home"), FakeDns.A to byteArrayOf(1, 2, 3, 4))).isEmpty())
        val whole = FakeDns.reply(DnsQuery.question(7, "x.home"), FakeDns.A to byteArrayOf(1, 2, 3, 4))
        assertEquals(listOf("1.2.3.4"), DnsQuery.answers(7, "x.home", whole).map { it.hostAddress })
        for (cut in 0 until whole.size) assertTrue(DnsQuery.answers(7, "x.home", whole.copyOf(cut)).isEmpty())
        assertTrue(DnsQuery.answers(7, "x.home", "not dns at all".toByteArray()).isEmpty())
    }

    @Test
    fun thePhonesOwnAnswerComesFirst() {
        FakeDns { q -> FakeDns.reply(q, FakeDns.A to byteArrayOf(1, 2, 3, 4)) }.use { dns ->
            val found = Lookup(servers = { listOf(dns.address) }).lookup("localhost")
            assertTrue(found.all { it.isLoopbackAddress })
            assertEquals(0, dns.questions)
        }
    }

    @Test
    fun withoutAServerThatKnowsTheNameItIsNotFound() {
        // .invalid never resolves (RFC 6761)
        assertThrows(UnknownHostException::class.java) { Lookup().lookup("control.gate.invalid") }
        // 0.0.0.0 is what a phone's resolver gave for a name of the network
        assertThrows(BlankAddressException::class.java) { Lookup().lookup("0.0.0.0") }
    }
}

class LookupThroughTest {
    private val loopback = InetAddress.getLoopbackAddress()

    @Test
    fun joinsAndLogsInWhereThePhonesResolverGivesABlankAddress() {
        // as on the phone this was written for: the resolver says 0.0.0.0, the network's DNS knows better
        var asked = 0
        val lookup = Lookup(
            own = { name -> if (name == "localhost") arrayOf(InetAddress.getByName("0.0.0.0")) else InetAddress.getAllByName(name) },
            servers = { asked++; emptyList() },
        )
        FakeControlNode().use { node ->
            val e = assertThrows(KiwiException::class.java) { Controller.fleetOf(Invite.parse(node.newInvite()), lookup) }
            assertEquals(Problem.BlankAddress, e.problem)
            assertEquals(1, asked)
            assertTrue(node.requests.isEmpty())
        }
    }

    @Test
    fun theAddressFromTheNetworksDnsIsStillCheckedAgainstTheName() {
        // the DNS server (played by `own` giving nothing, then the answer) points at the fake node,
        // whose certificate is for localhost: asked for by another name, it is not trusted
        FakeControlNode().use { node ->
            val pointing = Lookup(own = { arrayOf(loopback) })
            val byOtherName = node.url.replace("localhost", "control.gate.test")
            val e = assertThrows(KiwiException::class.java) { ControlApi(byOtherName, node.ca.pem, lookup = pointing).call("GET", "/api/ca") }
            assertEquals(Problem.NotTrusted, e.problem)
            assertEquals(200, ControlApi(node.url, node.ca.pem, lookup = pointing).call("GET", "/api/ca").status)
        }
    }

    @Test
    fun asksTheDnsServersWhereTheResolverKnowsNothing() {
        FakeDns { q -> FakeDns.reply(q, FakeDns.A to byteArrayOf(127, 0, 0, 1)) }.use { dns ->
            // port 53 is not ours to open in a test: the lookup's own query is tried above, its use here
            val found = DnsQuery.addresses("control.gate.test", dns.address, dns.port)
            val lookup = Lookup(own = { throw UnknownHostException(it) }, servers = { emptyList() })
            assertThrows(UnknownHostException::class.java) { lookup.lookup("control.gate.test") }
            assertEquals(listOf("127.0.0.1"), found.map { it.hostAddress })
        }
    }
}
