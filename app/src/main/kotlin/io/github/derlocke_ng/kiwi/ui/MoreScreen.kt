package io.github.derlocke_ng.kiwi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.derlocke_ng.kiwi.R
import io.github.derlocke_ng.kiwi.Session
import io.github.derlocke_ng.kiwi.core.Lock
import io.github.derlocke_ng.kiwi.core.MIN_PASSWORD

/** More: who this phone is to the fleet, and how its key is unlocked. */
@Composable
fun MoreScreen(
    state: Session.In,
    can: (Lock) -> Boolean,
    onSetLock: (lock: Lock, on: Boolean, password: String?) -> Unit,
    onLock: () -> Unit,
    onActivity: () -> Unit,
) {
    var newPassword by rememberSaveable { mutableStateOf(false) }
    // a lock may go while another stays
    val spare = state.locks.size > 1
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(Destination.More.label),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            style = MaterialTheme.typography.headlineLarge,
        )
        ListItem(
            headlineContent = { Text(state.membership.name) },
            supportingContent = { Text(state.membership.url.removePrefix("https://")) },
            overlineContent = { Text(stringResource(R.string.this_phone)) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.activity)) },
            supportingContent = { Text(stringResource(R.string.activity_what)) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onActivity),
        )

        Text(
            text = stringResource(R.string.unlock),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val hasPassword = Lock.Password in state.locks
        ListItem(
            headlineContent = { Text(stringResource(R.string.lock_password)) },
            supportingContent = { Text(stringResource(R.string.lock_password_what)) },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hasPassword && spare) {
                        TextButton(onClick = { onSetLock(Lock.Password, false, null) }, enabled = !state.busy) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                    TextButton(onClick = { newPassword = true }, enabled = !state.busy) {
                        Text(stringResource(if (hasPassword) R.string.change else R.string.set))
                    }
                }
            },
        )
        for ((lock, what, missing) in listOf(
            Triple(Lock.Biometric, R.string.lock_biometric_what, R.string.problem_no_biometric),
            Triple(Lock.ScreenLock, R.string.lock_screen_lock_what, R.string.problem_no_screen_lock),
        )) {
            val on = lock in state.locks
            val possible = can(lock)
            ListItem(
                headlineContent = { Text(stringResource(lock.label())) },
                supportingContent = { Text(stringResource(if (on || possible) what else missing)) },
                trailingContent = {
                    Switch(
                        checked = on,
                        onCheckedChange = { onSetLock(lock, it, null) },
                        enabled = !state.busy && if (on) spare else possible,
                    )
                },
            )
        }
        Trouble(state.problem, null, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        OutlinedButton(onClick = onLock, modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.lock_now))
        }
        val context = LocalContext.current
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        }
        Text(
            text = stringResource(R.string.version, version),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (newPassword) {
        var password by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newPassword = false },
            title = { Text(stringResource(R.string.lock_password)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordFields(password, { password = it }, again, { again = it }, R.string.new_password, true)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { newPassword = false; onSetLock(Lock.Password, true, password) },
                    enabled = password.length >= MIN_PASSWORD && password == again,
                ) {
                    Text(stringResource(R.string.set))
                }
            },
            dismissButton = {
                TextButton(onClick = { newPassword = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
