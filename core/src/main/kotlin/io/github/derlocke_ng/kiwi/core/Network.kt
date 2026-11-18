package io.github.derlocke_ng.kiwi.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** A machine as the fleet file has it: one entry of `kiwi-server list --porcelain`. */
data class Machine(
    val name: String,
    val hostname: String,
    val role: String,
    val target: String,
    /** Where it stands on the way to a running machine, and the next step in words. */
    val phase: String,
    val next: String,
    val modules: List<String>,
    /** The web addresses of its services; [adminSites] are the ones for the owner only. */
    val sites: List<String>,
    val adminSites: Set<String>,
    val errors: List<String>,
    val warnings: List<String>,
)

/** A web address a machine serves. */
data class Service(val address: String, val machine: String, val admin: Boolean)

/** The fleet as the control node lists it. */
data class Network(val domain: String, val machines: List<Machine>) {
    val services: List<Service>
        get() = machines.flatMap { m -> m.sites.map { Service(it, m.name, it in m.adminSites) } }
}

/** How a machine was at the last look. */
enum class State { Ok, Warn, Bad, Down, Unknown }

/** What the last look found on one machine: one entry of `kiwi-server status --porcelain`. */
data class Health(val machine: String, val state: State, val problems: List<String>, val notes: List<String>)

/** The control node's last look at every machine. [at] is its time, in seconds since 1970. */
data class Look(val at: Long?, val health: Map<String, Health>, val error: String?) {
    fun stateOf(machine: String): State = health[machine]?.state ?: State.Unknown
}

/** A task the control node ran or runs: an apply, an update, a backup. */
data class Job(val id: String, val action: String, val machines: List<String>, val user: String, val state: String)

/** One line of who did what, as the control node wrote it down. [time] is its own text. */
data class Entry(val time: String, val user: String, val what: String, val machines: List<String>)

/** The control node's Activity: the newest tasks and the newest lines of who did what. */
data class Activity(val jobs: List<Job>, val entries: List<Entry>)

/** A login of one of a machine's services. [password] is null where it has none to show. */
data class Login(val label: String, val address: String, val user: String, val password: String?, val note: String)

/** What the app reads from the control node. Nothing here changes anything there. */
object Reading {
    /** The machines of the fleet (`GET /api/machines`). */
    fun network(api: ControlApi): Network {
        val json = ask(api, "/api/machines")
        val machines = json.objects("hosts").map { h ->
            Machine(
                name = h.text("name") ?: throw KiwiException(Problem.BadAnswer),
                hostname = h.text("hostname").orEmpty(),
                role = h.text("role").orEmpty(),
                target = h.text("target").orEmpty(),
                phase = h.text("phase").orEmpty(),
                next = h.text("next").orEmpty(),
                modules = h.texts("modules"),
                sites = h.texts("sites"),
                adminSites = h.texts("admin_sites").toSet(),
                errors = h.texts("errors"),
                warnings = h.texts("warnings"),
            )
        }
        return Network(json.text("domain").orEmpty(), machines)
    }

    /** The last look at the machines (`GET /api/status`), or null when the control node has not looked yet. */
    fun look(api: ControlApi): Look? {
        val json = ask(api, "/api/status")
        if (json.isEmpty()) return null
        val health = json.objects("hosts").mapNotNull { h ->
            val name = h.text("host") ?: return@mapNotNull null
            val state = when (h.text("state")) {
                "ok" -> State.Ok
                "warn" -> State.Warn
                "bad" -> State.Bad
                "down" -> State.Down
                else -> State.Unknown
            }
            Health(name, state, h.objects("problems").mapNotNull { it.text("text") }, h.texts("notes"))
        }
        val at = (json["time"] as? JsonPrimitive)?.doubleOrNull?.toLong()
        return Look(at, health.associateBy { it.machine }, json.text("error"))
    }

    /** The newest tasks and who did what (`GET /api/activity`). */
    fun activity(api: ControlApi): Activity {
        val json = ask(api, "/api/activity")
        return Activity(
            json.objects("jobs").map { j ->
                Job(j.text("id").orEmpty(), j.text("action").orEmpty(), j.texts("hosts"), j.text("user").orEmpty(), j.text("state").orEmpty())
            },
            json.objects("entries").map { e ->
                Entry(e.text("time").orEmpty(), e.text("user").orEmpty(), e.text("what").orEmpty(), e.texts("hosts") + listOfNotNull(e.text("host")))
            },
        )
    }

    /**
     * The logins of a machine's services, with their passwords (`GET /api/hosts/<name>/logins`).
     * The control node writes down that they were shown. One older than 2.194.0 does not hand
     * them to a controller and says "log in", as every one does to a token gone stale.
     */
    fun logins(api: ControlApi, machine: String): List<Login> {
        if (!isControllerName(machine)) throw KiwiException(Problem.BadAnswer)
        val json = try {
            ask(api, "/api/hosts/$machine/logins", staleMeans = Problem.OldControlNode)
        } catch (e: KiwiException) {
            // "log in": an older control node, unless it says the same to what every one hands a controller
            if (e.problem == Problem.OldControlNode && api.call("GET", "/api/fleet/revision").status in setOf(401, 403)) {
                throw KiwiException(Problem.LoggedOut)
            }
            throw e
        }
        return json.objects("logins").map { l ->
            Login(
                label = l.text("label") ?: l.text("module").orEmpty(),
                address = l.text("address").orEmpty(),
                user = l.text("user").orEmpty(),
                password = l.text("password")?.takeIf { it.isNotEmpty() },
                note = l.text("note").orEmpty(),
            )
        }
    }

    private fun ask(api: ControlApi, path: String, staleMeans: Problem = Problem.LoggedOut): JsonObject {
        val answer = api.call("GET", path)
        return when (answer.status) {
            200 -> answer.json
            401, 403 -> throw KiwiException(staleMeans)
            404, 405 -> throw KiwiException(Problem.OldControlNode)
            else -> throw KiwiException(Problem.BadAnswer)
        }
    }

}

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.list(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

internal fun JsonObject.texts(key: String): List<String> =
    list(key).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

internal fun JsonObject.objects(key: String): List<JsonObject> = list(key).filterIsInstance<JsonObject>()
