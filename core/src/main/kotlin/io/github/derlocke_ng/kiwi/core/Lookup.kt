package io.github.derlocke_ng.kiwi.core

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom

/** The phone's resolver answered, but with no address at all (0.0.0.0), and nobody else knew the name. */
class BlankAddressException(host: String) : IOException("the phone's resolver gave a blank address for $host")

/**
 * Finds the address of a name. First the phone's own resolver ([own]). Where that gives nothing, or
 * the blank address, the DNS servers of the networks the phone is on ([servers]) are asked directly, as a
 * browser does: on some phones the resolver does not answer apps for names of the network.
 *
 * Whatever address comes out, the connection is still checked against the fleet's CA and the
 * name, so a wrong answer leads nowhere.
 *
 * With [networkFirst] the networks' DNS servers are asked before the phone's resolver: for the
 * pages of the network, whose names outside its private domain have one address at home and
 * another on the Internet. A resolver that looks past the network's DNS gives the one for
 * the Internet, which at home leads nowhere.
 */
class Lookup(
    private val own: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val servers: () -> List<InetAddress> = { emptyList() },
    private val networkFirst: Boolean = false,
    private val ask: (name: String, server: InetAddress) -> List<InetAddress> = { name, server -> DnsQuery.addresses(name, server) },
) : Dns {
    /** The same lookup, asking the networks' DNS servers first. */
    fun networkFirst(): Lookup = Lookup(own, servers, true, ask)

    override fun lookup(hostname: String): List<InetAddress> {
        // an address is no name to ask anyone about
        val name = !Regex("[0-9.]+").matches(hostname) && ':' !in hostname
        if (networkFirst && name) asked(hostname)?.let { return it }
        var blank = false
        try {
            val all = own(hostname)
            val real = all.filterNot { it.isAnyLocalAddress }
            if (real.isNotEmpty()) return real
            blank = all.isNotEmpty()
        } catch (e: UnknownHostException) {
            // ask the servers
        }
        if (!networkFirst && name) asked(hostname)?.let { return it }
        if (blank) throw BlankAddressException(hostname)
        throw UnknownHostException(hostname)
    }

    /** What the first DNS server that knows the name says, or null. */
    private fun asked(hostname: String): List<InetAddress>? {
        for (server in servers()) {
            val found = try {
                ask(hostname, server)
            } catch (e: IOException) {
                emptyList()
            }
            if (found.isNotEmpty()) return found
        }
        return null
    }
}

/** One question to one DNS server over UDP: the IPv4 addresses of a name. */
internal object DnsQuery {
    private const val A = 1
    private const val IN = 1
    private const val TIMEOUT_MS = 3000

    fun addresses(name: String, server: InetAddress, port: Int = 53): List<InetAddress> {
        val id = SecureRandom().nextInt(0x10000)
        val question = question(id, name)
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.send(DatagramPacket(question, question.size, server, port))
            val packet = DatagramPacket(ByteArray(1500), 1500)
            socket.receive(packet)
            // only what the server we asked sent counts
            if (packet.address != server || packet.port != port) return emptyList()
            return answers(id, name, packet.data.copyOf(packet.length))
        }
    }

    fun question(id: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun short(v: Int) {
            out.write(v shr 8 and 0xff)
            out.write(v and 0xff)
        }
        short(id)
        short(0x0100)   // a question, recursion wanted
        short(1)        // one question
        short(0)
        short(0)
        short(0)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) throw IOException("not a name: $name")
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        short(A)
        short(IN)
        return out.toByteArray()
    }

    /** The addresses in an answer to question [id], named [name] or what it is an alias of. Nothing when it is no such answer. */
    fun answers(id: Int, name: String, packet: ByteArray): List<InetAddress> = try {
        val data = DataInputStream(packet.inputStream())
        val gotId = data.readUnsignedShort()
        val flags = data.readUnsignedShort()
        val questions = data.readUnsignedShort()
        val answers = data.readUnsignedShort()
        data.skipBytes(4)
        val isAnswer = flags and 0x8000 != 0
        val failed = flags and 0x000f != 0
        if (gotId != id || !isAnswer || failed) {
            emptyList()
        } else {
            repeat(questions) {
                skipName(data)
                data.skipBytes(4)
            }
            val found = mutableListOf<InetAddress>()
            repeat(answers) {
                skipName(data)
                val type = data.readUnsignedShort()
                val kind = data.readUnsignedShort()
                data.skipBytes(4)
                val length = data.readUnsignedShort()
                val value = ByteArray(length).also(data::readFully)
                if (type == A && kind == IN && length == 4) found += InetAddress.getByAddress(name, value)
            }
            found.filterNot { it.isAnyLocalAddress }
        }
    } catch (e: IOException) {
        // cut short, or not DNS at all
        emptyList()
    }

    /** A name in a packet: labels up to an empty one, or up to a pointer to the rest. */
    private fun skipName(data: DataInputStream) {
        while (true) {
            val length = data.readUnsignedByte()
            if (length == 0) return
            if (length and 0xc0 == 0xc0) {
                data.skipBytes(1)
                return
            }
            if (data.skipBytes(length) != length) throw IOException("a name runs past the packet")
        }
    }
}
