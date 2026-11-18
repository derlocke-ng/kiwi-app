package io.github.derlocke_ng.kiwi.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.derlocke_ng.kiwi.R
import io.github.derlocke_ng.kiwi.Session
import io.github.derlocke_ng.kiwi.SessionViewModel

/** Join first, then the screen lock, then the app. */
@Composable
fun KiwiApp(model: SessionViewModel) {
    model.crash?.let { CrashReport(it, model::crashSeen) }
    val onLocalNetwork = rememberOnLocalNetwork()
    when (val session = model.session) {
        Session.Starting -> Surface(Modifier.fillMaxSize()) {}
        is Session.Out -> JoinScreen(session, Build.MODEL) { link, name, password ->
            onLocalNetwork { model.join(link, name, password) }
        }
        is Session.Locked -> {
            LaunchedEffect(Unit) { onLocalNetwork { model.unlockByItself() } }
            LockedScreen(
                session,
                { lock, password, afterAsking -> onLocalNetwork { model.unlock(lock, password, afterAsking) } },
                model::asked,
                model::forget,
            )
        }
        is Session.In -> Fleet(session, model)
    }
}

@Composable
private fun Fleet(session: Session.In, model: SessionViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val toTab = { destination: Destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        // a machine's page belongs to Machines
                        selected = current?.route?.substringBefore('/') == destination.route,
                        onClick = { toTab(destination) },
                        icon = {
                            Icon(painterResource(destination.icon), contentDescription = null)
                        },
                        label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            val toMachine = { name: String -> navController.navigate("${Destination.Machines.route}/$name") }
            NavHost(navController, startDestination = Destination.Home.route) {
                composable(Destination.Home.route) {
                    HomeScreen(model.seen, session.membership.url.removePrefix("https://"), model::refresh, toMachine, toTab)
                }
                composable(Destination.Services.route) { ServicesScreen(model.seen, model::refresh) }
                composable(Destination.Machines.route) { MachinesScreen(model.seen, model::refresh, toMachine) }
                composable("${Destination.Machines.route}/{name}") { entry ->
                    val name = entry.arguments?.getString("name").orEmpty()
                    MachineScreen(
                        seen = model.seen,
                        name = name,
                        logins = model.logins?.takeIf { it.first == name }?.second,
                        onRefresh = model::refresh,
                        onLogins = { show -> if (show) model.showLogins(name) else model.hideLogins() },
                    )
                }
                composable("${Destination.More.route}/activity") { ActivityScreen(model.activity, model::readActivity) }
                composable(Destination.More.route) {
                    MoreScreen(session, model::can, model::setLock, model::lock) { navController.navigate("${Destination.More.route}/activity") }
                }
            }
        }
    }
}

/** After the app stopped by itself: what it said, to copy and pass on. */
@Composable
private fun CrashReport(text: String, onSeen: () -> Unit) {
    val context = LocalContext.current
    val label = stringResource(R.string.app_name)
    AlertDialog(
        onDismissRequest = onSeen,
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.crash_what))
                SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(label, text))
            }) {
                Text(stringResource(R.string.copy))
            }
        },
        dismissButton = { TextButton(onClick = onSeen) { Text(stringResource(R.string.close)) } },
    )
}

/**
 * Runs what talks to the control node, after the phone was asked to let the app onto the local
 * network where it has to be asked (Android 17 and newer). A no does not stop it: the control
 * node may be reached another way, and if not, the problem says what to allow.
 */
@Composable
private fun rememberOnLocalNetwork(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        waiting?.invoke()
        waiting = null
    }
    return { then ->
        val allowed = Build.VERSION.SDK_INT < SessionViewModel.LOCAL_NETWORK_SINCE ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            then()
        } else {
            waiting = then
            ask.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}
