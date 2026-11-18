package io.github.derlocke_ng.kiwi.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.derlocke_ng.kiwi.R
import io.github.derlocke_ng.kiwi.Seen
import io.github.derlocke_ng.kiwi.Shown
import io.github.derlocke_ng.kiwi.core.Login
import io.github.derlocke_ng.kiwi.core.Machine
import io.github.derlocke_ng.kiwi.core.Service
import io.github.derlocke_ng.kiwi.core.State
import io.github.derlocke_ng.kiwi.ui.theme.Tone
import io.github.derlocke_ng.kiwi.ui.theme.tone
import java.text.DateFormat
import java.util.Date

@StringRes
fun State.label(): Int = when (this) {
    State.Ok -> R.string.state_ok
    State.Warn -> R.string.state_warn
    State.Bad -> R.string.state_bad
    State.Down -> R.string.state_down
    State.Unknown -> R.string.state_unknown
}

private fun Seen.stateOf(machine: Machine): State = look?.stateOf(machine.name) ?: State.Unknown

/** What is wrong with a machine, in the control node's words: the fleet file's errors, then the last look's. */
private fun Seen.troubleOf(machine: Machine): List<String> =
    machine.errors + look?.health?.get(machine.name)?.problems.orEmpty() + machine.warnings

private fun Seen.needsALook(machine: Machine): Boolean =
    stateOf(machine).let { it == State.Warn || it == State.Bad || it == State.Down } || machine.errors.isNotEmpty()

private fun Machine.what(): String = listOf(role, target).filter { it.isNotEmpty() }.joinToString(" · ")

/** The first part of a service's address: what it is called. */
private fun Service.name(): String = address.substringBefore('.')

private val Gutter = 16.dp

/**
 * Home: how the network is, in one card; what needs a look; then the machines and the services,
 * with a search field over all of it.
 */
@Composable
fun HomeScreen(
    seen: Seen,
    title: String,
    onRefresh: () -> Unit,
    onMachine: (String) -> Unit,
    onAll: (Destination) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val uris = LocalUriHandler.current
    Refreshing(seen, onRefresh) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gutter, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Trouble(seen.problem, seen.detail, Modifier.padding(horizontal = Gutter, vertical = 8.dp))
            val network = seen.network
            if (network == null) {
                if (seen.problem == null && !seen.loading) NothingYet()
                return@Column
            }
            val q = query.trim()
            val machines = network.machines.filter { m ->
                q.isEmpty() || m.name.contains(q, true) || m.role.contains(q, true) || m.modules.any { it.contains(q, true) }
            }
            val services = network.services.filter { q.isEmpty() || it.address.contains(q, true) || it.machine.contains(q, true) }

            if (q.isEmpty()) Summary(seen, title)

            val wanting = machines.filter(seen::needsALook)
            if (wanting.isNotEmpty()) {
                Heading(R.string.needs_a_look)
                Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    wanting.forEach { m ->
                        WideCard(
                            state = seen.stateOf(m),
                            letter = m.name,
                            title = m.name,
                            text = seen.troubleOf(m).firstOrNull() ?: stringResource(seen.stateOf(m).label()),
                        ) { onMachine(m.name) }
                    }
                }
            }
            if (machines.isNotEmpty()) {
                Heading(Destination.Machines.label) { onAll(Destination.Machines) }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(machines, key = { it.name }) { m -> MachineCard(m, seen.stateOf(m)) { onMachine(m.name) } }
                }
            }
            if (services.isNotEmpty()) {
                Heading(Destination.Services.label) { onAll(Destination.Services) }
                // two beside each other; all of them when searched for, else the first few
                val shown = if (q.isEmpty()) services.take(6) else services
                Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { s -> ServiceTile(s, Modifier.weight(1f)) { uris.openUri("https://" + s.address) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (machines.isEmpty() && services.isEmpty()) NothingYet()
        }
    }
}

/** How the network is, in one card in the color of its worst machine. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(seen: Seen, title: String) {
    val machines = seen.network?.machines.orEmpty()
    val states = machines.map { seen.stateOf(it) }
    val wanting = machines.count(seen::needsALook)
    val worst = when {
        states.any { it == State.Bad || it == State.Down } -> State.Bad
        wanting > 0 -> State.Warn
        seen.look == null || states.any { it == State.Unknown } -> State.Unknown
        else -> State.Ok
    }
    val tone = worst.tone()
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = tone.container, contentColor = tone.onContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = when {
                    machines.isEmpty() -> stringResource(R.string.home_no_machines)
                    wanting > 0 -> pluralStringResource(R.plurals.home_some_need, machines.size, wanting, machines.size)
                    seen.look == null -> stringResource(R.string.not_looked_yet)
                    else -> pluralStringResource(R.plurals.home_all_fine, machines.size, machines.size)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (machines.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (state in listOf(State.Ok, State.Warn, State.Bad, State.Down, State.Unknown)) {
                        val n = states.count { it == state }
                        if (n > 0) Count(n, state)
                    }
                }
            }
            LookLine(seen)
        }
    }
}

@Composable
private fun Count(n: Int, state: State) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(state)
            Text("$n", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(state.label()), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LookLine(seen: Seen) {
    val look = seen.look ?: return
    val at = look.at
    val error = look.error
    val text = when {
        error != null -> error
        at != null -> stringResource(
            R.string.looked_at,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at * 1000)),
        )
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/** Machines: every machine of the fleet, with how it was at the last look. */
@Composable
fun MachinesScreen(seen: Seen, onRefresh: () -> Unit, onMachine: (String) -> Unit) {
    Refreshing(seen, onRefresh) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Title(stringResource(Destination.Machines.label)) }
            item { Trouble(seen.problem, seen.detail) }
            val machines = seen.network?.machines.orEmpty()
            if (machines.isEmpty() && seen.problem == null && !seen.loading) item { NothingYet() }
            items(machines, key = { it.name }) { m ->
                val state = seen.stateOf(m)
                WideCard(state, m.name, m.name, m.what(), trailing = stringResource(state.label())) { onMachine(m.name) }
            }
        }
    }
}

/** Services: the web addresses the machines serve. A tap opens one. */
@Composable
fun ServicesScreen(seen: Seen, onRefresh: () -> Unit) {
    val uris = LocalUriHandler.current
    Refreshing(seen, onRefresh) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Title(stringResource(Destination.Services.label)) }
            item { Trouble(seen.problem, seen.detail) }
            val services = seen.network?.services.orEmpty()
            if (services.isEmpty() && seen.problem == null && !seen.loading) item { NothingYet() }
            items(services, key = { it.address }) { s ->
                ServiceTile(s, Modifier.fillMaxWidth(), address = true) { uris.openUri("https://" + s.address) }
            }
        }
    }
}

/** One machine: a big header in the color of its state, then what the fleet file and the last look say about it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MachineScreen(
    seen: Seen,
    name: String,
    logins: Shown<List<Login>>?,
    onRefresh: () -> Unit,
    onLogins: (show: Boolean) -> Unit,
) {
    val machine = seen.network?.machines?.firstOrNull { it.name == name }
    val uris = LocalUriHandler.current
    // the logins go when the page does
    DisposableEffect(name) { onDispose { onLogins(false) } }
    Refreshing(seen, onRefresh) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val state = machine?.let { seen.stateOf(it) } ?: State.Unknown
            val tone = state.tone()
            Column(
                Modifier.fillMaxWidth().background(tone.container).padding(horizontal = Gutter, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // on the header's own color the circle is the pills' color, or it would not show
                Letter(name, Tone(tone.color, MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), MaterialTheme.colorScheme.onSurface), 56.dp)
                Text(name, color = tone.onContainer, style = MaterialTheme.typography.displaySmall)
                if (machine != null) {
                    Text(machine.hostname, color = tone.onContainer, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(stringResource(state.label()), dot = state)
                        if (machine.role.isNotEmpty()) Pill(machine.role)
                        if (machine.target.isNotEmpty()) Pill(machine.target)
                    }
                }
            }
            if (machine == null) {
                NothingYet()
                return@Column
            }
            Lines(R.string.problems, seen.troubleOf(machine))
            Lines(R.string.next, listOf(machine.next).filter { it.isNotBlank() })
            Lines(R.string.notes, seen.look?.health?.get(name)?.notes.orEmpty())
            if (machine.modules.isNotEmpty()) {
                Heading(R.string.modules)
                FlowRow(
                    Modifier.padding(horizontal = Gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    machine.modules.forEach { Pill(it, MaterialTheme.colorScheme.surfaceContainerHighest) }
                }
            }
            if (machine.sites.isNotEmpty()) {
                Heading(Destination.Services.label)
                Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    machine.sites.forEach { site ->
                        ServiceTile(Service(site, name, site in machine.adminSites), Modifier.fillMaxWidth(), address = true) {
                            uris.openUri("https://$site")
                        }
                    }
                }
                Heading(R.string.logins)
                Logins(logins, onLogins)
            }
        }
    }
}

/** The logins of a machine's services: asked for by the owner, passwords behind another tap, gone with the page. */
@Composable
private fun Logins(logins: Shown<List<Login>>?, onLogins: (show: Boolean) -> Unit) {
    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (logins == null) {
            Text(stringResource(R.string.logins_what), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = { onLogins(true) }) { Text(stringResource(R.string.logins_show)) }
            return@Column
        }
        if (logins.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Trouble(logins.problem, logins.detail)
        val found = logins.value ?: return@Column
        // while passwords may be on the screen it is kept out of screenshots and the list of recent apps
        val window = LocalActivity.current?.window
        DisposableEffect(window) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        if (found.isEmpty()) Text(stringResource(R.string.nothing_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
        found.forEach { LoginCard(it) }
        TextButton(onClick = { onLogins(false) }) { Text(stringResource(R.string.logins_hide)) }
    }
}

@Composable
private fun LoginCard(login: Login) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    val password = login.password
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(login.label, style = MaterialTheme.typography.titleMedium)
            if (login.address.isNotEmpty()) {
                Text(login.address, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (login.user.isNotEmpty()) SelectionContainer { Text(login.user) }
            if (password != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (shown) password else "••••••••",
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                    )
                    TextButton(onClick = { shown = !shown }) {
                        Text(stringResource(if (shown) R.string.hide else R.string.show))
                    }
                    TextButton(onClick = {
                        val clip = ClipData.newPlainText(login.label, password)
                        // keeps it out of the keyboard's preview of what was copied
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                        }
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                    }) {
                        Text(stringResource(R.string.copy))
                    }
                }
            }
            if (login.note.isNotEmpty()) {
                Text(login.note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Refreshing(seen: Seen, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    PullToRefreshBox(isRefreshing = seen.loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        content()
    }
}

/** A card across the screen: a letter in the color of the state, a title, a line or two, and where it leads. */
@Composable
private fun WideCard(state: State, letter: String, title: String, text: String, trailing: String? = null, onClick: () -> Unit) {
    val tone = state.tone()
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Letter(letter, tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (trailing != null) {
                Text(trailing, color = tone.color, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A machine in a row of cards. */
@Composable
private fun MachineCard(machine: Machine, state: State, onClick: () -> Unit) {
    val tone = state.tone()
    Card(onClick = onClick, modifier = Modifier.width(148.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Letter(machine.name, tone, 48.dp)
            Spacer(Modifier.height(8.dp))
            Text(machine.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(
                text = machine.what(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(state.label()),
                color = tone.color,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** A service: what it is called, where it runs, a lock where it is for the owner only. With [address], its whole address. */
@Composable
private fun ServiceTile(service: Service, modifier: Modifier = Modifier, address: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = modifier) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Letter(service.name(), Tone(scheme.secondary, scheme.secondaryContainer, scheme.onSecondaryContainer))
            Column(Modifier.weight(1f)) {
                Text(service.name(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (address) service.address else service.machine,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (service.admin) {
                Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.admin), modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            }
        }
    }
}

/** The first letter of a name in a circle: what stands for a machine or a service. */
@Composable
internal fun Letter(name: String, tone: Tone, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tone.container), contentAlignment = Alignment.Center) {
        Text(
            text = name.take(1).uppercase(),
            color = tone.onContainer,
            fontWeight = FontWeight.SemiBold,
            style = if (size >= 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun Pill(text: String, color: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), dot: State? = null) {
    Surface(shape = CircleShape, color = color, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dot != null) Dot(dot)
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Lines(@StringRes title: Int, lines: List<String>) {
    if (lines.isEmpty()) return
    Heading(title)
    Card(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            lines.forEachIndexed { i, line ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Text(line, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

/** What a part of a screen is called, and a way to all of it where there is one. */
@Composable
internal fun Heading(@StringRes title: Int, onAll: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp, top = 16.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        if (onAll != null) TextButton(onClick = onAll) { Text(stringResource(R.string.all)) }
    }
}

@Composable
internal fun Title(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.headlineLarge,
    )
}

@Composable
internal fun Dot(state: State) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(state.tone().color))
}

@Composable
internal fun NothingYet() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.nothing_yet),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
