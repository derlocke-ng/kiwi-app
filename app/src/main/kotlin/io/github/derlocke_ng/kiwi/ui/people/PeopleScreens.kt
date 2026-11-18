package io.github.derlocke_ng.kiwi.ui.people

import android.content.ClipData
import android.content.ClipboardManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.derlocke_ng.kiwi.R
import io.github.derlocke_ng.kiwi.Session
import io.github.derlocke_ng.kiwi.Shown
import io.github.derlocke_ng.kiwi.core.GameServer
import io.github.derlocke_ng.kiwi.core.Offer
import io.github.derlocke_ng.kiwi.core.Problem
import io.github.derlocke_ng.kiwi.core.Registry
import io.github.derlocke_ng.kiwi.core.Running
import io.github.derlocke_ng.kiwi.core.State
import io.github.derlocke_ng.kiwi.core.Visit
import io.github.derlocke_ng.kiwi.ui.Dot
import io.github.derlocke_ng.kiwi.ui.Heading
import io.github.derlocke_ng.kiwi.ui.Letter
import io.github.derlocke_ng.kiwi.ui.Page
import io.github.derlocke_ng.kiwi.ui.Title
import io.github.derlocke_ng.kiwi.ui.text
import io.github.derlocke_ng.kiwi.ui.theme.tone
import java.text.DateFormat
import java.util.Date

/*
 * The part for the people who use a network. Nothing here knows of a controller, its key or
 * the control node's API: it shows what the network's registry offers.
 */

private val Gutter = 16.dp

/** A problem in the words of someone who does not run the network. */
@StringRes
private fun Problem.textForPeople(): Int = when (this) {
    Problem.NoName, Problem.NoAnswer, Problem.BlankAddress -> R.string.problem_nothing_there
    Problem.NotTrusted -> R.string.page_not_trusted
    else -> text()
}

@Composable
private fun Trouble(problem: Problem?, detail: String?, modifier: Modifier = Modifier) {
    if (problem == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(problem.textForPeople()), color = MaterialTheme.colorScheme.error)
        if (detail != null) {
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Neither in a fleet nor using a network: the two ways in. */
@Composable
fun StartScreen(onUse: () -> Unit, onRun: () -> Unit) {
    Page {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Way(R.string.start_use, R.string.start_use_what, onUse)
        Way(R.string.start_run, R.string.start_run_what, onRun)
    }
}

@Composable
private fun Way(@StringRes title: Int, @StringRes what: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(what), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Find a network by a name of it. */
@Composable
fun FindScreen(state: Session.Out, onFind: (name: String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Page {
        Text(stringResource(R.string.start_use), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.find_how), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.find_name)) },
            enabled = !state.busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (name.isNotBlank()) onFind(name) }),
        )
        Trouble(state.problem, state.detail)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = { onFind(name) }, enabled = !state.busy && name.isNotBlank()) {
            Text(stringResource(R.string.find))
        }
    }
}

/** What the network offers: how it is in one card, its services, its game servers, and the way to an account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    visit: Visit,
    offered: Shown<Registry>,
    onRefresh: () -> Unit,
    onOpen: (url: String, title: String) -> Unit,
    onLeave: () -> Unit,
) {
    val registry = offered.value
    var leaving by rememberSaveable { mutableStateOf(false) }
    val account = stringResource(R.string.account)
    val status = stringResource(R.string.status_page)
    val gamePage = stringResource(R.string.game_page)
    Surface(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = offered.loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { Column(Modifier.padding(horizontal = Gutter)) { Title(registry?.network ?: visit.url.removePrefix("https://")) } }
                item { Trouble(offered.problem, offered.detail, Modifier.padding(horizontal = Gutter, vertical = 8.dp)) }
                if (registry != null) {
                    item { Summary(registry) }
                    if (registry.join.isNotEmpty() || registry.status.isNotEmpty()) {
                        item {
                            Row(Modifier.padding(horizontal = Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (registry.join.isNotEmpty()) Button(onClick = { onOpen(registry.join, account) }) { Text(account) }
                                if (registry.status.isNotEmpty()) OutlinedButton(onClick = { onOpen(registry.status, status) }) { Text(status) }
                            }
                        }
                    }
                    if (registry.services.isNotEmpty()) {
                        item { Heading(R.string.nav_services) }
                        items(registry.services, key = { it.url }) { offer ->
                            OfferCard(offer, Modifier.padding(horizontal = Gutter, vertical = 4.dp)) { onOpen(offer.url, offer.label) }
                        }
                    }
                    if (registry.games.isNotEmpty()) {
                        item { Heading(R.string.games) }
                        items(registry.games, key = { "game " + it.name }) { game ->
                            GameCard(game, Modifier.padding(horizontal = Gutter, vertical = 4.dp))
                        }
                        if (registry.play.isNotEmpty()) {
                            item {
                                OutlinedButton(onClick = { onOpen(registry.play, gamePage) }, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
                                    Text(gamePage)
                                }
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = Gutter).padding(top = 24.dp)) {
                        Text(
                            text = visit.url.removePrefix("https://") + " · " + stringResource(R.string.network_ca, visit.caMark),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { leaving = true }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.leave)) }
                    }
                }
            }
        }
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.leave_title)) },
            text = { Text(stringResource(R.string.leave_what)) },
            confirmButton = { TextButton(onClick = { leaving = false; onLeave() }) { Text(stringResource(R.string.leave)) } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** How the network's services are, in one card: green when all work, amber when some do not. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(registry: Registry) {
    val all = registry.services.size
    val failing = registry.services.count { !it.ok }
    val tone = (if (all == 0) State.Unknown else if (failing > 0) State.Warn else State.Ok).tone()
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = tone.container, contentColor = tone.onContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = when {
                    all == 0 -> stringResource(R.string.offers_none)
                    failing > 0 -> pluralStringResource(R.plurals.offers_some_fail, all, failing, all)
                    else -> pluralStringResource(R.plurals.offers_all_work, all, all)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            registry.checked?.let { at ->
                Text(
                    text = stringResource(R.string.asked_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at * 1000))),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OfferCard(offer: Offer, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val state = if (offer.ok) State.Ok else State.Bad
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Letter(offer.label, state.tone(), 48.dp)
            Column(Modifier.weight(1f)) {
                Text(offer.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = offer.about.ifEmpty { offer.host },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!offer.ok) Text(stringResource(R.string.works_not), color = state.tone().color, style = MaterialTheme.typography.labelMedium)
                val locale = LocalLocale.current.platformLocale
                offer.uptime?.let {
                    Text(
                        text = stringResource(R.string.uptime, String.format(locale, if (it >= 99.95) "%.0f" else "%.1f", it)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/** A game server: a tap copies the address a player types into the game. */
@Composable
private fun GameCard(game: GameServer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (state, label) = when (game.state) {
        Running.Yes -> State.Ok to R.string.game_running
        Running.Asleep -> State.Ok to R.string.game_asleep
        Running.Stopped -> State.Bad to R.string.game_stopped
        Running.Unknown -> State.Unknown to R.string.game_unknown
    }
    Card(
        onClick = { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(game.name, game.address)) },
        enabled = game.address.isNotEmpty(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (game.title.isEmpty()) game.name else game.title + " · " + game.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            if (game.address.isNotEmpty()) Text(game.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(state)
                Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
