package io.github.derlocke_ng.kiwi.ui

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.derlocke_ng.kiwi.R
import io.github.derlocke_ng.kiwi.Session
import io.github.derlocke_ng.kiwi.core.Problem
import io.github.derlocke_ng.kiwi.core.controllerNameFrom
import io.github.derlocke_ng.kiwi.core.isControllerName
import io.github.derlocke_ng.kiwi.lock.askScreenLock

/** What the owner reads for a problem: short, and what to do. */
@StringRes
fun Problem.text(): Int = when (this) {
    Problem.NotAnInvite -> R.string.problem_not_an_invite
    Problem.BadName -> R.string.problem_bad_name
    Problem.NoAnswer -> R.string.problem_no_answer
    Problem.NotAControlNode -> R.string.problem_not_a_control_node
    Problem.WrongFleet -> R.string.problem_wrong_fleet
    Problem.InviteRefused -> R.string.problem_invite_refused
    Problem.UnknownController -> R.string.problem_unknown_controller
    Problem.WrongKey -> R.string.problem_wrong_key
    Problem.NoScreenLock -> R.string.problem_no_screen_lock
    Problem.Locked -> R.string.problem_locked
    Problem.KeyLost -> R.string.problem_key_lost
}

/** Not in a fleet yet: paste the invite link, name the phone, join. */
@Composable
fun JoinScreen(state: Session.Out, model: String, onJoin: (link: String, name: String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(controllerNameFrom(model)) }
    Page {
        Text(stringResource(R.string.join_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.join_how), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.join_link)) },
            enabled = !state.busy,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.join_name)) },
            supportingText = { Text(stringResource(R.string.problem_bad_name)) },
            isError = !isControllerName(name),
            enabled = !state.busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
        )
        Trouble(state.problem)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(
            onClick = { onJoin(link, name) },
            enabled = !state.busy && link.isNotBlank() && isControllerName(name),
        ) {
            Text(stringResource(R.string.join))
        }
    }
}

/** Joined: the key rests wrapped until the screen lock opens it. */
@Composable
fun LockedScreen(
    state: Session.Locked,
    onUnlock: (afterAsking: Boolean) -> Unit,
    onAsked: (declined: Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val activity = LocalActivity.current as? FragmentActivity
    val title = stringResource(R.string.unlock_title)
    val subtitle = stringResource(R.string.unlock_what)
    LaunchedEffect(state.ask) {
        if (state.ask && activity != null) {
            onAsked(false)
            askScreenLock(activity, title, subtitle) { yes -> if (yes) onUnlock(true) else onAsked(true) }
        }
    }

    var forgetting by rememberSaveable { mutableStateOf(false) }
    Page {
        Text(state.membership.url.removePrefix("https://"), style = MaterialTheme.typography.headlineLarge)
        Text(
            stringResource(R.string.this_phone_is, state.membership.name),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Trouble(state.problem)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = { onUnlock(false) }, enabled = !state.busy) {
            Text(stringResource(R.string.unlock))
        }
        TextButton(onClick = { forgetting = true }, enabled = !state.busy) {
            Text(stringResource(R.string.join_again))
        }
    }
    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(stringResource(R.string.forget_title)) },
            text = { Text(stringResource(R.string.forget_what, state.membership.name)) },
            confirmButton = {
                TextButton(onClick = { forgetting = false; onForget() }) { Text(stringResource(R.string.forget)) }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun Trouble(problem: Problem?) {
    if (problem != null) Text(stringResource(problem.text()), color = MaterialTheme.colorScheme.error)
}
