package io.github.derlocke_ng.kiwi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** What the control node answered: the HTTP status and the JSON object (empty when it sent none). */
class Answer(val status: Int, val json: JsonObject) {
    fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * HTTPS to the control node. With [caPem] the certificate is checked against the fleet's CA and
 * nothing else; without it nothing is checked, which is only for fetching the CA itself (the
 * invite's code says whether it is the right one).
 */
class ControlApi(url: String, private val caPem: String?, var token: String? = null) {
    private val base = url.trimEnd('/')
    private val sockets: SSLSocketFactory = try {
        if (caPem != null) trusting(caPem) else trustingAnyone()
    } catch (e: Exception) {
        // what was shown as the CA is no certificate
        throw KiwiException(Problem.NotAControlNode, e)
    }

    fun call(method: String, path: String, body: JsonObject? = null): Answer {
        val conn = try {
            URL(base + path).openConnection() as HttpsURLConnection
        } catch (e: Exception) {
            throw KiwiException(Problem.NoAnswer, e)
        }
        try {
            conn.sslSocketFactory = sockets
            if (caPem == null) conn.setHostnameVerifier { _, _ -> true }
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val raw = (if (status < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            val json = try {
                Json.parseToJsonElement(raw.decodeToString()).jsonObject
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }
            return Answer(status, json)
        } catch (e: UnknownHostException) {
            throw KiwiException(Problem.NoName, e)
        } catch (e: SSLException) {
            throw KiwiException(Problem.NotTrusted, e)
        } catch (e: IOException) {
            throw KiwiException(Problem.NoAnswer, e)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        fun trusting(caPem: String): SSLSocketFactory =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(FleetTrust(caPem)), SecureRandom()) }.socketFactory

        fun trustingAnyone(): SSLSocketFactory {
            val anyone = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf(anyone), SecureRandom()) }.socketFactory
        }
    }
}

/**
 * Trusts what the fleet's CA signed, and nothing else: not the phone's own list of CAs, and
 * none of its rules for public web sites (which a private CA cannot meet). The path from the
 * server's certificate to the CA is checked here; that the certificate is for the name asked
 * is checked by the connection, as always.
 */
private class FleetTrust(caPem: String) : X509TrustManager {
    private val factory = CertificateFactory.getInstance("X.509")
    private val cas: List<X509Certificate> =
        factory.generateCertificates(ByteArrayInputStream(caPem.toByteArray())).map { it as X509Certificate }

    init {
        require(cas.isNotEmpty()) { "no certificate in the CA" }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        // what the server sent, without the CA itself: the CA is where the path ends
        val sent = chain.filter { it !in cas }
        if (sent.isEmpty()) throw CertificateException("the server sent no certificate of its own")
        try {
            val rules = PKIXParameters(cas.map { TrustAnchor(it, null) }.toSet()).apply { isRevocationEnabled = false }
            CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(sent), rules)
        } catch (e: GeneralSecurityException) {
            throw CertificateException("not signed by the fleet's CA: ${e.message}", e)
        }
        val uses = sent[0].extendedKeyUsage
        if (uses != null && SERVER !in uses && ANY_USE !in uses) throw CertificateException("not a server's certificate")
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("the app is no server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = cas.toTypedArray()

    private companion object {
        const val SERVER = "1.3.6.1.5.5.7.3.1"
        const val ANY_USE = "2.5.29.37.0"
    }
}

internal fun jsonOf(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
