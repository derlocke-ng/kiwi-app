package io.github.derlocke_ng.kiwi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/*
 * The part for the people who use a network without running it. Nothing here needs a
 * controller's key or the control node's API: it reads the network's registry
 * (`https://status.<control node>/api/registry`), which asks for no login.
 */

/** A service as the registry offers it: what it is called, what it is for, where it is, whether it works. */
data class Offer(
    val label: String,
    val about: String,
    val url: String,
    val ok: Boolean,
    /** In percent over the last days, or null where it was never asked. */
    val uptime: Double?,
    val onion: String,
) {
    val host: String get() = hostIn(url)
}

/** A game server: [address] is what a player types into the game. */
data class GameServer(val name: String, val title: String, val address: String, val state: Running)

enum class Running { Yes, Asleep, Stopped, Unknown }

/** The network as a person on it uses it. [join], [play] and [status] are pages, or empty. */
data class Registry(
    val network: String,
    val domain: String,
    /** When the services were last asked, in seconds since 1970, or null. */
    val checked: Long?,
    val services: List<Offer>,
    val games: List<GameServer>,
    val join: String,
    val play: String,
    val status: String,
) {
    /**
     * Whether [host] is a name of this network: one of its private domain, or one the registry
     * itself names. Only for those does the app take the fleet's CA.
     */
    fun has(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        return (domain.isNotEmpty() && name.endsWith(".$domain")) ||
            name in (services.map { it.url } + listOf(join, play, status)).filter { it.isNotEmpty() }.map { hostIn(it) }
    }
}

/** A network this phone uses: where its registry is and its CA. Nothing here is secret. */
data class Visit(val url: String, val caPem: String) {
    /** The first eight hex digits of the SHA-256 of the CA: what an invite of that network ends with. */
    val caMark: String get() = sha256Hex(caPem.toByteArray()).take(8)
}

/** This phone as a user of a network, as it rests in [file]. Every call blocks. */
class Visitor(private val file: File, private val lookup: Lookup = Lookup()) {
    /** The network this phone uses, or null. */
    fun visit(): Visit? {
        val json = try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: Exception) {
            return null
        }
        return Visit(json.text("url") ?: return null, json.text("ca") ?: return null)
    }

    /**
     * Find the network from what was typed: a machine's name (`papaya.home`), its address on
     * the LAN, or the status page's name. A machine leads to the status name from
     * `/.well-known/kiwi`; only that lead is taken from it, since its certificate cannot be
     * checked yet (and at an address never fits). The CA is taken as the status name shows it,
     * on first use, and everything after is checked against it. Keeps the network.
     */
    fun find(typed: String): Pair<Visit, Registry> {
        val first = "https://" + hostOf(typed)
        val lead = ControlApi(first, caPem = null, lookup = lookup).call("GET", "/.well-known/kiwi")
        val at = if (lead.status in 300..399) originOf(first, lead.location) else first
        val shown = ControlApi(at, caPem = null, lookup = lookup).call("GET", "/api/ca")
        val ca = shown.text("ca")
        if (shown.status != 200 || ca.isNullOrEmpty()) throw KiwiException(Problem.NotANetwork)
        val visit = Visit(at, ca)
        val registry = registry(visit)
        keep(visit)
        return visit to registry
    }

    /** Read the registry, over a connection checked against the network's CA. */
    fun registry(visit: Visit): Registry {
        val answer = ControlApi(visit.url, visit.caPem, lookup = lookup).call("GET", "/api/registry")
        val json = answer.json
        if (answer.status != 200 || json.text("kind") != "kiwi-registry") throw KiwiException(Problem.NotANetwork)
        return Registry(
            network = json.text("network").orEmpty().ifEmpty { "Kiwi" },
            domain = json.text("domain").orEmpty().lowercase().trim('.'),
            checked = json.number("checked")?.toLong()?.takeIf { it > 0 },
            services = json.objects("services").mapNotNull { s ->
                val url = s.text("url")?.takeIf { isPage(it) } ?: return@mapNotNull null
                Offer(
                    label = s.text("label")?.takeIf { it.isNotEmpty() } ?: hostIn(url).substringBefore('.'),
                    about = s.text("about").orEmpty(),
                    url = url,
                    ok = (s["ok"] as? JsonPrimitive)?.content == "true",
                    uptime = s.number("uptime"),
                    onion = s.text("onion").orEmpty(),
                )
            },
            games = json.objects("games").mapNotNull { g ->
                val name = g.text("name")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val state = when (g.text("state")) {
                    "running" -> Running.Yes
                    "asleep" -> Running.Asleep
                    "stopped" -> Running.Stopped
                    else -> Running.Unknown
                }
                GameServer(name, g.text("title").orEmpty(), g.text("address").orEmpty(), state)
            },
            join = json.text("join")?.takeIf { isPage(it) }.orEmpty(),
            play = json.text("play")?.takeIf { isPage(it) }.orEmpty(),
            status = json.text("status")?.takeIf { isPage(it) }.orEmpty(),
        )
    }

    /** Stop using the network on this phone. */
    fun leave() {
        file.delete()
    }

    private fun keep(visit: Visit) {
        val json = JsonObject(mapOf("url" to JsonPrimitive(visit.url), "ca" to JsonPrimitive(visit.caPem)))
        val next = File(file.parentFile, file.name + ".new")
        next.writeText(json.toString())
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}

/**
 * Where the registry of the network is whose control page is at [controlUrl]: at the status
 * name beside it (`control.<host>` and `status.<host>`), and failing that at the control
 * page's own name, which answers it too.
 */
fun registryPlaces(controlUrl: String): List<String> {
    val url = controlUrl.trimEnd('/')
    val host = url.removePrefix("https://")
    return listOfNotNull(if (host.startsWith("control.")) "https://status." + host.removePrefix("control.") else null, url)
}

/** The name or address in what was typed: with or without `https://`, a path, a port. */
internal fun hostOf(typed: String): String {
    val host = typed.trim().lowercase().removePrefix("https://").removePrefix("http://")
        .substringBefore('/').substringBefore('?').substringBefore('#').trimEnd('.')
    if (!Regex("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:[0-9]{1,5})?").matches(host)) throw KiwiException(Problem.NotANetwork)
    return host
}

/** Where a redirect from [from] leads, as `https://name[:port]`: nothing but https is followed. */
private fun originOf(from: String, location: String?): String {
    val to = try {
        URI("$from/").resolve(location ?: throw KiwiException(Problem.NotANetwork))
    } catch (e: IllegalArgumentException) {
        throw KiwiException(Problem.NotANetwork, e)
    }
    if (to.scheme != "https" || to.host.isNullOrEmpty()) throw KiwiException(Problem.NotANetwork)
    return "https://" + to.host + if (to.port > 0) ":${to.port}" else ""
}

private fun isPage(url: String): Boolean = url.startsWith("https://") && hostIn(url).isNotEmpty()

internal fun hostIn(url: String): String = try {
    URI(url).host.orEmpty().lowercase()
} catch (e: Exception) {
    ""
}
