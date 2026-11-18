package io.github.derlocke_ng.kiwi.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class TunnelTest {
    private val name = "cloud.papaya.home"

    /** The phone's resolver does not know the name; the app's own lookup does. */
    private val lookup = Lookup(own = { host ->
        if (host == name) arrayOf(InetAddress.getByName("127.0.0.1")) else throw UnknownHostException(host)
    })

    /** A page view: goes through the tunnel, and takes only what the CA signed for the name. */
    private fun browser(tunnel: Tunnel, ca: TestCa): OkHttpClient {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("ca", ca.cert) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val sockets = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", tunnel.port)))
            .sslSocketFactory(sockets, trust.trustManagers[0] as X509TrustManager)
            .build()
    }

    private fun page(node: FakeControlNode) = Request.Builder().url(node.url.replace("localhost", name) + "/api/ca").build()

    @Test
    fun `a page of the network is reached by the app's own lookup, secured end to end`() {
        val ca = TestCa()
        FakeControlNode(ca, tls = ca.serverContext(name)).use { node ->
            Tunnel(lookup) { host, _ -> host == name }.use { tunnel ->
                val client = browser(tunnel, ca)
                repeat(2) {
                    client.newCall(page(node)).execute().use { answer ->
                        assertEquals(200, answer.code)
                        assertTrue(answer.body.string().contains("BEGIN CERTIFICATE"))
                    }
                }
                // a server that is not the network's is not taken, tunnel or not
                assertThrows(IOException::class.java) { browser(tunnel, TestCa("someone else")).newCall(page(node)).execute() }
            }
        }
    }

    @Test
    fun `an address where nothing answers does not stand in the way of one that does`() {
        val ca = TestCa()
        // the first address refuses, the second is the server
        val two = Lookup(own = { arrayOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1")) })
        FakeControlNode(ca, tls = ca.serverContext(name)).use { node ->
            Tunnel(two) { host, _ -> host == name }.use { tunnel ->
                browser(tunnel, ca).newCall(page(node)).execute().use { assertEquals(200, it.code) }
                assertEquals(null, tunnel.trouble(name))
            }
        }
    }

    @Test
    fun `the tunnel leads only to the names of the network`() {
        val ca = TestCa()
        FakeControlNode(ca, tls = ca.serverContext(name)).use { node ->
            Tunnel(lookup) { _, port -> port == 443 }.use { tunnel ->
                assertThrows(IOException::class.java) { browser(tunnel, ca).newCall(page(node)).execute() }
                assertEquals(emptyList<String>(), node.requests)
            }
        }
    }

    private fun said(tunnel: Tunnel, ask: String): String =
        Socket("127.0.0.1", tunnel.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(ask.toByteArray())
            socket.getInputStream().bufferedReader().readLine().orEmpty()
        }

    @Test
    fun `it is no proxy for anything else`() {
        Tunnel(lookup) { _, _ -> true }.use { tunnel ->
            assertEquals("HTTP/1.1 405 Method Not Allowed", said(tunnel, "GET http://$name/ HTTP/1.1\r\nHost: $name\r\n\r\n"))
            assertEquals("HTTP/1.1 405 Method Not Allowed", said(tunnel, "CONNECT $name HTTP/1.1\r\n\r\n"))
            // a name nobody knows, and a port where nothing is
            assertEquals("HTTP/1.1 502 Bad Gateway", said(tunnel, "CONNECT nowhere.home:443 HTTP/1.1\r\n\r\n"))
            assertEquals("HTTP/1.1 502 Bad Gateway", said(tunnel, "CONNECT $name:1 HTTP/1.1\r\n\r\n"))
            // and it says what stood in the way
            assertTrue(tunnel.trouble("nowhere.home").orEmpty(), tunnel.trouble("nowhere.home").orEmpty().startsWith("no address"))
            assertTrue(tunnel.trouble(name).orEmpty(), tunnel.trouble(name).orEmpty().startsWith("127.0.0.1: "))
        }
    }

    @Test
    fun `it listens where it says, whatever the system calls its loopback address`() {
        Tunnel(lookup) { _, _ -> true }.use { tunnel ->
            assertEquals("127.0.0.1:${tunnel.port}", tunnel.address)
            val (host, port) = tunnel.address.split(":")
            // reached by the address as it is written, with no name looked up
            Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port.toInt()).use { assertTrue(it.isConnected) }
            assertEquals("127.0.0.1", host)
        }
    }

    @Test
    fun `closed, it answers no more`() {
        val tunnel = Tunnel(lookup) { _, _ -> true }
        val port = tunnel.port
        tunnel.close()
        assertThrows(IOException::class.java) { Socket("127.0.0.1", port).use { it.getInputStream().read() } }
    }
}
