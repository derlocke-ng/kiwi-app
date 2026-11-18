package io.github.derlocke_ng.kiwi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/**
 * What the control node answered: the HTTP status and the JSON object (empty when it sent none).
 * [location] is where a redirect leads; it is never followed by itself.
 */
class Answer(val status: Int, val json: JsonObject, val location: String? = null) {
    fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * HTTPS to the control node. With [caPem] the certificate is checked against the fleet's CA and
 * nothing else; without it nothing is checked, which is only for fetching the CA itself (the
 * invite's code says whether it is the right one). [lookup] finds the address of its name.
 */
class ControlApi(url: String, private val caPem: String?, var token: String? = null, lookup: Lookup = Lookup()) {
    private val base = url.trimEnd('/')
    private val client: OkHttpClient = try {
        val trust = if (caPem != null) FleetTrust(caPem) else Anyone
        val sockets = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }.socketFactory
        shared.newBuilder()
            .dns(lookup)
            .sslSocketFactory(sockets, trust)
            .apply { if (caPem == null) hostnameVerifier { _, _ -> true } }
            .build()
    } catch (e: Exception) {
        // what was shown as the CA is no certificate
        throw KiwiException(Problem.NotAControlNode, e)
    }

    fun call(method: String, path: String, body: JsonObject? = null): Answer {
        val request = try {
            Request.Builder()
                .url(base + path)
                .header("Accept", "application/json")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .method(method, body?.toString()?.toRequestBody(JSON) ?: if (method == "GET") null else EMPTY)
                .build()
        } catch (e: IllegalArgumentException) {
            throw KiwiException(Problem.NoAnswer, e)
        }
        try {
            client.newCall(request).execute().use { response ->
                val json = try {
                    Json.parseToJsonElement(response.body.string()).jsonObject
                } catch (e: Exception) {
                    JsonObject(emptyMap())
                }
                return Answer(response.code, json, response.header("Location"))
            }
        } catch (e: IOException) {
            // every address of the name is tried; the last failure is thrown, the others ride along.
            // A server that answered with the wrong certificate says more than an address where nothing is.
            val all = listOf<Throwable>(e) + e.suppressed
            val untrusted = all.firstOrNull { it is SSLException }
            throw when {
                untrusted != null -> KiwiException(Problem.NotTrusted, untrusted)
                e is BlankAddressException -> KiwiException(Problem.BlankAddress, e)
                e is UnknownHostException -> KiwiException(Problem.NoName, e)
                else -> KiwiException(Problem.NoAnswer, e)
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val EMPTY = ByteArray(0).toRequestBody(null)

        /** One pool of connections and threads for every control node client; each sets its own trust. */
        val shared: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /** For the one question asked before the fleet's CA is known: what the fleet's CA is. */
        val Anyone = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
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

/**
 * Whether [cert] is a server's certificate for [host] that the CA in [caPem] signed, and is
 * valid now: for a page of the network shown inside the app, where the phone's own list of CAs
 * does not know the fleet's.
 */
fun isOfFleet(caPem: String, cert: X509Certificate, host: String): Boolean = try {
    FleetTrust(caPem).checkServerTrusted(arrayOf(cert), "UNKNOWN")
    cert.isFor(host.lowercase().trimEnd('.'))
} catch (e: Exception) {
    false
}

/** The names a certificate is for: `*.` stands for one first part of a name, and no more. */
private fun X509Certificate.isFor(host: String): Boolean =
    subjectAlternativeNames.orEmpty()
        .filter { it.size >= 2 && it[0] == 2 }
        .mapNotNull { (it[1] as? String)?.lowercase()?.trimEnd('.') }
        .any { name -> name == host || (name.startsWith("*.") && '.' in host && host.substringAfter('.') == name.drop(2)) }

internal fun jsonOf(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
