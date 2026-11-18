package io.github.derlocke_ng.kiwi.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.derlocke_ng.kiwi.Seen
import io.github.derlocke_ng.kiwi.Session
import io.github.derlocke_ng.kiwi.Shown
import io.github.derlocke_ng.kiwi.core.Activity
import io.github.derlocke_ng.kiwi.core.AgeKey
import io.github.derlocke_ng.kiwi.core.ControlApi
import io.github.derlocke_ng.kiwi.core.Entry
import io.github.derlocke_ng.kiwi.core.GameServer
import io.github.derlocke_ng.kiwi.core.Health
import io.github.derlocke_ng.kiwi.core.Job
import io.github.derlocke_ng.kiwi.core.Lock
import io.github.derlocke_ng.kiwi.core.Login
import io.github.derlocke_ng.kiwi.core.Look
import io.github.derlocke_ng.kiwi.core.Machine
import io.github.derlocke_ng.kiwi.core.Membership
import io.github.derlocke_ng.kiwi.core.Network
import io.github.derlocke_ng.kiwi.core.Offer
import io.github.derlocke_ng.kiwi.core.Problem
import io.github.derlocke_ng.kiwi.core.Registry
import io.github.derlocke_ng.kiwi.core.Running
import io.github.derlocke_ng.kiwi.core.State
import io.github.derlocke_ng.kiwi.core.Visit
import io.github.derlocke_ng.kiwi.ui.people.FindScreen
import io.github.derlocke_ng.kiwi.ui.people.NetworkScreen
import io.github.derlocke_ng.kiwi.ui.people.StartScreen
import io.github.derlocke_ng.kiwi.ui.theme.KiwiTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the screens with a made-up fleet, to look at: nothing is compared.
 * Only with -Ppictures (app/build.gradle.kts); they land in app/build/pictures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class PicturesTest {
    @Before
    fun onlyWhenAsked() = assumeTrue(System.getProperty("kiwi.pictures") == "true")

    private fun machine(name: String, role: String, target: String, modules: List<String>, sites: List<String>, next: String = "") =
        Machine(name, "$name.home", role, target, if (next.isEmpty()) "applied" else "install", next, modules, sites.map { "$it.$name.home" }, setOf("control.$name.home", "pihole.$name.home"), emptyList(), emptyList())

    private val network = Network(
        "home",
        listOf(
            machine("gate", "master", "debian", listOf("vpn-server", "dns", "control"), listOf("control", "pihole", "vpn")),
            machine("papaya", "node", "ucore", listOf("cloud", "vault", "paperless"), listOf("cloud", "vault", "paperless")),
            machine("sorbet", "node", "ucore", listOf("media", "downloader"), listOf("media", "downloads")),
            machine("lab1", "bare", "coreos", emptyList(), emptyList(), next = "write the ISO to a stick"),
        ),
    )
    private val look = Look(
        1795000000L,
        mapOf(
            "gate" to Health("gate", State.Ok, emptyList(), listOf("applied yesterday")),
            "papaya" to Health("papaya", State.Warn, listOf("a restart is waiting: the kernel was updated"), emptyList()),
            "sorbet" to Health("sorbet", State.Down, listOf("unreachable: connection timed out"), emptyList()),
        ),
        null,
    )
    private val seen = Seen(network = network, look = look)

    private fun picture(name: String, dark: Boolean = false, content: @Composable () -> Unit) =
        captureRoboImage("build/pictures/$name.png") { KiwiTheme(darkTheme = dark) { Surface { content() } } }

    private val logins = listOf(
        Login("Nextcloud", "https://cloud.papaya.home", "admin", "correct horse battery", ""),
        Login("Vaultwarden", "https://vault.papaya.home", "", null, "The first account is yours."),
    )
    private val activity = Activity(
        listOf(Job("20261118-054012-a1b2c3", "apply", listOf("papaya"), "owner", "done"), Job("20261117-221500-d4e5f6", "update", listOf("gate", "sorbet"), "owner", "failed")),
        listOf(
            Entry("2026-11-18T05:40:12+0100", "phone", "fetched the fleet", emptyList()),
            Entry("2026-11-18T05:39:00+0100", "owner", "logins shown", listOf("papaya")),
        ),
    )
    private val fine = Seen(network = network, look = Look(1795000000L, network.machines.associate { it.name to Health(it.name, State.Ok, emptyList(), emptyList()) }, null))
    private val membership = Membership("https://control.gate.home", "pixel", "0123", "")

    @Test
    fun home() = picture("home") { HomeScreen(seen, "control.gate.home", {}, {}, {}) }

    @Test
    fun homeDark() = picture("home-dark", dark = true) { HomeScreen(seen, "control.gate.home", {}, {}, {}) }

    @Test
    fun homeFine() = picture("home-fine") { HomeScreen(fine, "control.gate.home", {}, {}, {}) }

    @Test
    fun machines() = picture("machines") { MachinesScreen(seen, {}, {}) }

    @Test
    fun machine() = picture("machine") { MachineScreen(seen, "papaya", null, {}, {}) }

    @Test
    fun machineLogins() = picture("machine-logins", dark = true) { MachineScreen(seen, "papaya", Shown(value = logins), {}, {}) }

    @Test
    fun services() = picture("services") { ServicesScreen(seen, {}) }

    @Test
    fun activity() = picture("activity") { ActivityScreen(Shown(value = activity), {}) }

    @Test
    fun more() = picture("more") {
        MoreScreen(Session.In(membership, ControlApi("https://control.gate.home", null), AgeKey.new(), setOf(Lock.Password, Lock.Biometric)), { true }, { _, _, _ -> }, {}, {})
    }

    // the part for the people who use the network
    private val registry = Registry(
        "Kiwi", "home", 1795000000L,
        listOf(
            Offer("Jellyfin", "Films and series", "https://media.sorbet.home/", false, 91.3, ""),
            Offer("Nextcloud", "Files, calendar and contacts", "https://cloud.papaya.home/", true, 100.0, ""),
            Offer("Paperless", "", "https://paperless.papaya.home/", true, 99.2, ""),
            Offer("Vaultwarden", "Passwords", "https://vault.papaya.home/", true, null, ""),
        ),
        listOf(GameServer("survival", "Minecraft", "play.gate.home:25565", Running.Yes), GameServer("creative", "Minecraft", "play.gate.home:25566", Running.Asleep)),
        "https://join.gate.home/", "https://play.gate.home/", "https://status.gate.home/",
    )
    private val visit = Visit("https://status.gate.home", "made up")

    @Test
    fun start() = picture("start") { StartScreen({}, {}) }

    @Test
    fun find() = picture("find") { FindScreen(Session.Out(problem = Problem.NoAnswer, detail = "ConnectException: Failed to connect to papaya.home")) {} }

    @Test
    fun people() = picture("people") { NetworkScreen(visit, Shown(value = registry), {}, { _, _ -> }, {}) }

    @Test
    fun peopleDark() = picture("people-dark", dark = true) { NetworkScreen(visit, Shown(value = registry), {}, { _, _ -> }, {}) }
}
