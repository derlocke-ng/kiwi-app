package io.github.derlocke_ng.kiwi.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.derlocke_ng.kiwi.R
import io.github.derlocke_ng.kiwi.Seen
import io.github.derlocke_ng.kiwi.core.Machine
import io.github.derlocke_ng.kiwi.core.Service
import io.github.derlocke_ng.kiwi.core.State
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

@Composable
private fun State.color(): Color = when (this) {
    State.Ok -> MaterialTheme.colorScheme.primary
    State.Warn -> MaterialTheme.colorScheme.tertiary
    State.Bad, State.Down -> MaterialTheme.colorScheme.error
    State.Unknown -> MaterialTheme.colorScheme.outline
}

private fun Seen.stateOf(machine: Machine): State = look?.stateOf(machine.name) ?: State.Unknown

/** What is wrong with a machine, in the control node's words: the fleet file's errors, then the last look's. */
private fun Seen.troubleOf(machine: Machine): List<String> =
    machine.errors + look?.health?.get(machine.name)?.problems.orEmpty() + machine.warnings

private fun Seen.needsALook(machine: Machine): Boolean =
    stateOf(machine).let { it == State.Warn || it == State.Bad || it == State.Down } || machine.errors.isNotEmpty()

/** Home: a search field, then rows of cards: what needs a look, the machines, the services. */
@Composable
fun HomeScreen(seen: Seen, onRefresh: () -> Unit, onMachine: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val uris = LocalUriHandler.current
    Refreshing(seen, onRefresh) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
            val network = seen.network
            Trouble(seen.problem, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (network == null) {
                if (seen.problem == null && !seen.loading) NothingYet()
                return@Column
            }
            LookLine(seen)
            val q = query.trim()
            val machines = network.machines.filter { m ->
                q.isEmpty() || m.name.contains(q, true) || m.role.contains(q, true) || m.modules.any { it.contains(q, true) }
            }
            val services = network.services.filter { q.isEmpty() || it.address.contains(q, true) || it.machine.contains(q, true) }

            CardRow(R.string.needs_a_look, machines.filter(seen::needsALook)) { m ->
                Tile(m.name, seen.troubleOf(m).firstOrNull() ?: stringResource(seen.stateOf(m).label()), seen.stateOf(m)) {
                    onMachine(m.name)
                }
            }
            CardRow(Destination.Machines.label, machines) { m ->
                Tile(m.name, m.role, seen.stateOf(m)) { onMachine(m.name) }
            }
            CardRow(Destination.Services.label, services) { s ->
                Tile(s.address.substringBefore('.'), s.machine, null) { uris.openUri("https://" + s.address) }
            }
            if (machines.isEmpty() && services.isEmpty()) NothingYet()
        }
    }
}

/** Machines: every machine of the fleet, with how it was at the last look. */
@Composable
fun MachinesScreen(seen: Seen, onRefresh: () -> Unit, onMachine: (String) -> Unit) {
    Refreshing(seen, onRefresh) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Title(stringResource(Destination.Machines.label)) }
            item { Trouble(seen.problem, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            val machines = seen.network?.machines.orEmpty()
            if (machines.isEmpty() && seen.problem == null && !seen.loading) item { NothingYet() }
            items(machines, key = { it.name }) { m ->
                ListItem(
                    headlineContent = { Text(m.name) },
                    supportingContent = {
                        Text(listOf(m.role, m.target, stringResource(seen.stateOf(m).label())).filter { it.isNotEmpty() }.joinToString(" · "))
                    },
                    leadingContent = { Dot(seen.stateOf(m)) },
                    modifier = Modifier.clickable { onMachine(m.name) },
                )
            }
        }
    }
}

/** Services: the web addresses the machines serve. A tap opens one. */
@Composable
fun ServicesScreen(seen: Seen, onRefresh: () -> Unit) {
    val uris = LocalUriHandler.current
    Refreshing(seen, onRefresh) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Title(stringResource(Destination.Services.label)) }
            item { Trouble(seen.problem, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            val services = seen.network?.services.orEmpty()
            if (services.isEmpty() && seen.problem == null && !seen.loading) item { NothingYet() }
            items(services, key = { it.address }) { s -> ServiceItem(s) { uris.openUri("https://" + s.address) } }
        }
    }
}

/** One machine: a big header, then what the fleet file and the last look say about it. */
@Composable
fun MachineScreen(seen: Seen, name: String, onRefresh: () -> Unit) {
    val machine = seen.network?.machines?.firstOrNull { it.name == name }
    val uris = LocalUriHandler.current
    Refreshing(seen, onRefresh) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                text = name,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp),
                style = MaterialTheme.typography.displaySmall,
            )
            if (machine == null) {
                NothingYet()
                return@Column
            }
            val state = seen.stateOf(machine)
            Text(
                text = listOf(machine.hostname, machine.role, machine.target).filter { it.isNotEmpty() }.joinToString(" · "),
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(state)
                Text(stringResource(state.label()), style = MaterialTheme.typography.titleMedium)
            }
            Lines(R.string.problems, seen.troubleOf(machine))
            Lines(R.string.next, listOf(machine.next).filter { it.isNotBlank() })
            Lines(R.string.notes, seen.look?.health?.get(name)?.notes.orEmpty())
            Lines(R.string.modules, listOf(machine.modules.joinToString(", ")).filter { it.isNotEmpty() })
            if (machine.sites.isNotEmpty()) {
                Heading(Destination.Services.label)
                machine.sites.forEach { site ->
                    ServiceItem(Service(site, name, site in machine.adminSites)) { uris.openUri("https://$site") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Refreshing(seen: Seen, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    PullToRefreshBox(isRefreshing = seen.loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        content()
    }
}

@Composable
private fun LookLine(seen: Seen) {
    val look = seen.look
    val at = look?.at
    val error = look?.error
    val text = when {
        look == null -> stringResource(R.string.not_looked_yet)
        error != null -> error
        at != null -> stringResource(
            R.string.looked_at,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at * 1000)),
        )
        else -> return
    }
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** A row of cards under a heading, in the manner of F-Droid's Discover. Nothing when there is nothing. */
@Composable
private fun <T> CardRow(@StringRes title: Int, things: List<T>, card: @Composable (T) -> Unit) {
    if (things.isEmpty()) return
    Heading(title)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(things) { card(it) }
    }
}

@Composable
private fun Tile(title: String, text: String, state: State?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(168.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state != null) Dot(state)
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ServiceItem(service: Service, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(service.address) },
        supportingContent = {
            Text(if (service.admin) service.machine + " · " + stringResource(R.string.admin) else service.machine)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Lines(@StringRes title: Int, lines: List<String>) {
    if (lines.isEmpty()) return
    Heading(title)
    lines.forEach { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
}

@Composable
private fun Heading(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
        style = MaterialTheme.typography.headlineLarge,
    )
}

@Composable
private fun Dot(state: State) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(state.color()))
}

@Composable
private fun NothingYet() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.nothing_yet),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
