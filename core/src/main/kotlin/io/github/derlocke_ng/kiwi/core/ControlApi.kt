package io.github.derlocke_ng.kiwi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
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
    private val sockets: SSLSocketFactory = if (caPem != null) trusting(caPem) else trustingAnyone()

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
        } catch (e: IOException) {
            throw KiwiException(Problem.NoAnswer, e)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        fun trusting(caPem: String): SSLSocketFactory {
            val certs = CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(caPem.toByteArray()))
            require(certs.isNotEmpty()) { "no certificate in the CA" }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            certs.forEachIndexed { i, cert -> store.setCertificateEntry("fleet-$i", cert) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, SecureRandom()) }.socketFactory
        }

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

internal fun jsonOf(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
