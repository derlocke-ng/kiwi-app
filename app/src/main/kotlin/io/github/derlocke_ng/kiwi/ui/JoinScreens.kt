package io.github.derlocke_ng.kiwi.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.derlocke_ng.kiwi.R
import io.github.derlocke_ng.kiwi.Session
import io.github.derlocke_ng.kiwi.core.Invite
import io.github.derlocke_ng.kiwi.core.Lock
import io.github.derlocke_ng.kiwi.core.MIN_PASSWORD
import io.github.derlocke_ng.kiwi.core.Problem
import io.github.derlocke_ng.kiwi.core.controllerNameFrom
import io.github.derlocke_ng.kiwi.core.isControllerName
import io.github.derlocke_ng.kiwi.lock.askOwner

/** What the owner reads for a problem: short, and what to do. */
@StringRes
fun Problem.text(): Int = when (this) {
    Problem.NotAnInvite -> R.string.problem_not_an_invite
    Problem.BadName -> R.string.problem_bad_name
    Problem.NoName -> R.string.problem_no_name
    Problem.BlankAddress -> R.string.problem_blank_address
    Problem.NoAnswer -> R.string.problem_no_answer
    Problem.NotTrusted -> R.string.problem_not_trusted
    Problem.NotAControlNode -> R.string.problem_not_a_control_node
    Problem.WrongFleet -> R.string.problem_wrong_fleet
    Problem.InviteRefused -> R.string.problem_invite_refused
    Problem.UnknownController -> R.string.problem_unknown_controller
    Problem.WrongKey -> R.string.problem_wrong_key
    Problem.LoggedOut -> R.string.problem_logged_out
    Problem.OldControlNode -> R.string.problem_old_control_node
    Problem.BadAnswer -> R.string.problem_bad_answer
    Problem.NoScreenLock -> R.string.problem_no_screen_lock
    Problem.NoBiometric -> R.string.problem_no_biometric
    Problem.WeakPassword -> R.string.problem_weak_password
    Problem.WrongPassword -> R.string.problem_wrong_password
    Problem.Locked -> R.string.problem_locked
    Problem.LastLock -> R.string.problem_last_lock
    Problem.Unexpected -> R.string.problem_unexpected
    Problem.KeyLost -> R.string.problem_key_lost
}

/** What a lock is called. */
@StringRes
fun Lock.label(): Int = when (this) {
    Lock.Password -> R.string.lock_password
    Lock.Biometric -> R.string.lock_biometric
    Lock.ScreenLock -> R.string.lock_screen_lock
}

/** Not in a fleet yet: paste the invite link, name the phone, choose a password or none, join. */
@Composable
fun JoinScreen(state: Session.Out, model: String, onJoin: (link: String, name: String, password: String?) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(controllerNameFrom(model)) }
    // a password is never saved with the screen's state
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val passwordFits = password.isEmpty() || (password.length >= MIN_PASSWORD && password == again)
    var scanning by rememberSaveable { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> scanning = allowed }
    if (scanning) {
        BackHandler { scanning = false }
        Box(Modifier.fillMaxSize()) {
            // anything that is not an invite is looked past
            Scanner(
                onText = { text ->
                    if (runCatching { Invite.parse(text) }.isSuccess) {
                        link = text.trim()
                        scanning = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.scan_how), color = Color.White)
                Button(onClick = { scanning = false }) { Text(stringResource(R.string.cancel)) }
            }
        }
        return
    }
    Page {
        Text(stringResource(R.string.join_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.join_how), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { camera.launch(Manifest.permission.CAMERA) }, enabled = !state.busy) {
            Text(stringResource(R.string.scan))
        }
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
        PasswordFields(password, { password = it }, again, { again = it }, R.string.join_password, !state.busy)
        Trouble(state.problem, state.detail)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(
            onClick = { onJoin(link, name, password.ifEmpty { null }) },
            enabled = !state.busy && link.isNotBlank() && isControllerName(name) && passwordFits,
        ) {
            Text(stringResource(R.string.join))
        }
    }
}

/** Joined, the key locked: every lock that is set opens it. */
@Composable
fun LockedScreen(
    state: Session.Locked,
    onUnlock: (lock: Lock, password: String?, afterAsking: Boolean) -> Unit,
    onAsked: (declined: Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val activity = LocalActivity.current as? FragmentActivity
    val title = stringResource(R.string.unlock_title)
    val subtitle = stringResource(R.string.unlock_what)
    val cancel = stringResource(R.string.cancel)
    LaunchedEffect(state.ask) {
        val lock = state.ask
        if (lock != null && activity != null) {
            onAsked(false)
            askOwner(activity, lock, title, subtitle, cancel) { yes -> if (yes) onUnlock(lock, null, true) else onAsked(true) }
        }
    }

    var password by remember { mutableStateOf("") }
    var forgetting by rememberSaveable { mutableStateOf(false) }
    Page {
        Text(state.membership.url.removePrefix("https://"), style = MaterialTheme.typography.headlineLarge)
        Text(
            stringResource(R.string.this_phone_is, state.membership.name),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Trouble(state.problem, state.detail)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (Lock.Password in state.locks) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.lock_password)) },
                enabled = !state.busy,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Button(
                onClick = { onUnlock(Lock.Password, password, false); password = "" },
                enabled = !state.busy && password.isNotEmpty(),
            ) {
                Text(stringResource(R.string.unlock))
            }
        }
        for (lock in listOf(Lock.Biometric, Lock.ScreenLock)) {
            if (lock in state.locks) {
                OutlinedButton(onClick = { onUnlock(lock, null, false) }, enabled = !state.busy) {
                    Text(stringResource(lock.label()))
                }
            }
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

/** A new password, typed twice. The second field shows once the first has something in it. */
@Composable
fun PasswordFields(
    password: String,
    onPassword: (String) -> Unit,
    again: String,
    onAgain: (String) -> Unit,
    @StringRes label: Int,
    enabled: Boolean,
) {
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    OutlinedTextField(
        value = password,
        onValueChange = onPassword,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(R.string.problem_weak_password)) },
        isError = password.isNotEmpty() && password.length < MIN_PASSWORD,
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = keyboard,
    )
    if (password.isNotEmpty()) {
        OutlinedTextField(
            value = again,
            onValueChange = onAgain,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.password_again)) },
            isError = again.isNotEmpty() && again != password,
            enabled = enabled,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = keyboard,
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

/** A problem in the owner's words and, for the problems of the connection, what the system said. */
@Composable
fun Trouble(problem: Problem?, detail: String?, modifier: Modifier = Modifier) {
    if (problem == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(problem.text()), color = MaterialTheme.colorScheme.error)
        if (detail != null) {
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
