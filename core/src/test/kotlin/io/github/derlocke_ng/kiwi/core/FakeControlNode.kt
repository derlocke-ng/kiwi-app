package io.github.derlocke_ng.kiwi.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kage.Age
import kage.crypto.x25519.X25519Recipient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** A CA made for one test run. */
class TestCa(name: String = "kiwi test CA") {
    private val keys: KeyPair = newKeys()
    private val subject = X500Name("CN=$name")
    val cert: X509Certificate = sign(subject, keys, ca = true, host = null)
    val pem: String = "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) +
        "\n-----END CERTIFICATE-----\n"

    /** A TLS context for a server called [host]. */
    fun serverContext(host: String, expired: Boolean = false, forServers: Boolean = true): SSLContext {
        val leafKeys = newKeys()
        val leaf = sign(X500Name("CN=$host"), leafKeys, ca = false, host = host, expired = expired, forServers = forServers)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        store.setKeyEntry("leaf", leafKeys.private, CharArray(0), arrayOf(leaf, cert))
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        return SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
    }

    private fun sign(
        who: X500Name,
        whose: KeyPair,
        ca: Boolean,
        host: String?,
        expired: Boolean = false,
        forServers: Boolean = true,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val from = Date(now - if (expired) 3_600_000 else 60_000)
        val until = Date(now + if (expired) -60_000 else 3_600_000)
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(now + if (ca) 0 else 1), from, until, who, whose.public)
        if (!ca) {
            val use = if (forServers) KeyPurposeId.id_kp_serverAuth else KeyPurposeId.id_kp_emailProtection
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(use))
        }
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        if (host != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, host)))
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun newKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
}

/**
 * The control node's side of joining and logging in, as `lib/kiwiserver/control.py` and `web.py`
 * in kiwi-server do it: one invite, used once; a challenge encrypted to the controller's key.
 */
class FakeControlNode(
    val ca: TestCa = TestCa(),
    tlsFrom: TestCa = ca,
    shownCa: String? = ca.pem,
    val old: Boolean = false,
    tls: SSLContext = tlsFrom.serverContext("localhost"),
) : AutoCloseable {
    /** What `GET /api/machines` and `GET /api/status` answer: `list --porcelain` and the last `status --porcelain`. */
    var machines = "{}"
    var status = "{}"
    val controllers = mutableMapOf<String, String>()
    val requests = mutableListOf<String>()
    private val tokens = mutableMapOf<String, String>()
    private var invite: String? = null
    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String get() = "https://localhost:${server.address.port}"

    init {
        server.httpsConfigurator = HttpsConfigurator(tls)
        server.createContext("/") { x ->
            requests += "${x.requestMethod} ${x.requestURI.path}"
            val body = try {
                Json.parseToJsonElement(x.requestBody.readBytes().decodeToString()).jsonObject
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }
            fun text(key: String) = (body[key] as? JsonPrimitive)?.content ?: ""
            when ("${x.requestMethod} ${x.requestURI.path}") {
                "GET /api/ca" ->
                    if (shownCa == null) x.send(404, "error" to "no CA yet") else x.send(200, "ca" to shownCa, "store" to "0123456789abcdef")
                "POST /api/join" -> {
                    var name = text("name")
                    val ok = invite != null && text("code") == invite &&
                        Regex("[a-z0-9][a-z0-9-]{0,27}").matches(name) && Regex("age1[0-9a-z]{58}").matches(text("key")) &&
                        text("key") !in controllers.values
                    if (!ok) {
                        x.send(403, "error" to "refused")
                    } else {
                        val base = name
                        var n = 2
                        while (name in controllers) name = "$base-${n++}"
                        invite = null
                        controllers[name] = text("key")
                        x.send(200, "name" to name)
                    }
                }
                "POST /api/controller/hello" -> {
                    val key = controllers[text("name")]
                    if (key == null) {
                        x.send(404, "error" to "unknown")
                    } else {
                        val token = "token-${tokens.size}-${System.nanoTime()}"
                        tokens[token] = text("name")
                        val out = ByteArrayOutputStream()
                        Age.encryptStream(listOf(X25519Recipient.decode(key)), ByteArrayInputStream(token.toByteArray()), out, false)
                        x.send(200, "challenge" to Base64.getEncoder().encodeToString(out.toByteArray()))
                    }
                }
                "GET /api/fleet/revision" -> {
                    val who = tokens[(x.requestHeaders.getFirst("Authorization") ?: "").removePrefix("Bearer ")]
                    if (who == null) x.send(401, "error" to "who?") else x.send(200, "revision" to "rev-for-$who")
                }
                "GET /api/status", "GET /api/machines" -> {
                    val who = tokens[(x.requestHeaders.getFirst("Authorization") ?: "").removePrefix("Bearer ")]
                    val machines = x.requestURI.path == "/api/machines"
                    when {
                        // an older control node: the status only for the page's login, no list at all
                        old && machines -> x.send(404, "error" to "not found")
                        who == null || old -> x.send(401, "error" to "log in")
                        else -> x.sendText(200, if (machines) this.machines else status)
                    }
                }
                else -> x.send(404, "error" to "not found")
            }
        }
        server.start()
    }

    /** An invite link as the page's "Add a computer" makes it. */
    fun newInvite(): String {
        val code = "abcdefghjkmnpqrstuvw.${sha256Hex(ca.pem.toByteArray()).take(8)}"
        invite = code
        return "$url/#join=$code"
    }

    override fun close() = server.stop(0)

    private fun HttpExchange.send(status: Int, vararg pairs: Pair<String, String>) =
        sendText(status, JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) }).toString())

    private fun HttpExchange.sendText(status: Int, text: String) {
        val raw = text.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, raw.size.toLong())
        responseBody.use { it.write(raw) }
    }
}
