package io.github.derlocke_ng.kiwi.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The way out for the pages shown inside the app. A web view finds addresses with the phone's
 * resolver, which on some phones gives apps a blank address for a name of the network. So the
 * web view is told to go through this door on the phone itself (an HTTP proxy that only knows
 * `CONNECT`): the name is found by [lookup], as for the app's own connections, and from then
 * on the bytes are passed along untouched. The connection stays secured from the web view to
 * the server; nothing can be read or changed here.
 *
 * Any app on the phone can knock at this door, so it opens only to what [allowed] says: the
 * names of the network, which those apps reach by themselves as well.
 */
class Tunnel(private val lookup: Lookup, private val allowed: (host: String, port: Int) -> Boolean) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val open: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private val workers: ExecutorService = Executors.newCachedThreadPool { work -> Thread(work, "kiwi-tunnel").apply { isDaemon = true } }

    /** Where the door is: `127.0.0.1` and this port. */
    val port: Int get() = server.localPort

    init {
        workers.execute {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                open += client
                workers.execute { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        var far: Socket? = null
        try {
            client.soTimeout = HEAD_TIMEOUT_MS
            val (host, port) = target(head(client.getInputStream())) ?: return client.answer("405 Method Not Allowed")
            if (!allowed(host, port)) return client.answer("403 Forbidden")
            far = reach(host, port) ?: return client.answer("502 Bad Gateway")
            open += far
            client.soTimeout = 0
            client.answer("200 Connection Established", close = false)
            val back = far
            workers.execute { pass(back.getInputStream(), client.getOutputStream(), back, client) }
            pass(client.getInputStream(), far.getOutputStream(), client, far)
        } catch (e: IOException) {
            shut(client)
            far?.let { shut(it) }
        }
    }

    /** What was asked, up to the empty line. Null when it is too long or ends early. */
    private fun head(from: InputStream): String? {
        val bytes = StringBuilder()
        while (bytes.length < MAX_HEAD) {
            val b = from.read()
            if (b < 0) return null
            bytes.append(b.toChar())
            if (bytes.endsWith("\r\n\r\n")) return bytes.toString()
        }
        return null
    }

    /** The name and port of `CONNECT name:port HTTP/1.1`. */
    private fun target(head: String?): Pair<String, Int>? {
        val words = head?.lineSequence()?.firstOrNull()?.split(' ') ?: return null
        if (words.size != 3 || words[0] != "CONNECT") return null
        val port = words[1].substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val host = words[1].substringBeforeLast(':').removePrefix("[").removeSuffix("]").lowercase()
        return if (host.isEmpty()) null else host to port
    }

    private fun reach(host: String, port: Int): Socket? {
        val addresses = try {
            lookup.lookup(host)
        } catch (e: IOException) {
            return null
        }
        for (address in addresses) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                return socket
            } catch (e: IOException) {
                shut(socket)
            }
        }
        return null
    }

    /** Pass along what comes until one side ends; then both are closed. */
    private fun pass(from: InputStream, to: OutputStream, one: Socket, other: Socket) {
        try {
            from.copyTo(to)
        } catch (e: IOException) {
            // one side is gone
        } finally {
            shut(one)
            shut(other)
        }
    }

    private fun Socket.answer(status: String, close: Boolean = true) {
        try {
            getOutputStream().apply {
                write("HTTP/1.1 $status\r\n\r\n".toByteArray())
                flush()
            }
        } finally {
            if (close) shut(this)
        }
    }

    private fun shut(socket: Socket) {
        open -= socket
        runCatching { socket.close() }
    }

    override fun close() {
        runCatching { server.close() }
        synchronized(open) { open.toList() }.forEach { shut(it) }
        workers.shutdownNow()
    }

    private companion object {
        const val MAX_HEAD = 8192
        const val HEAD_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
