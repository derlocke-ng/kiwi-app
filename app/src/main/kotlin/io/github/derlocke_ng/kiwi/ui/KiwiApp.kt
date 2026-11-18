package io.github.derlocke_ng.kiwi.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.derlocke_ng.kiwi.Session
import io.github.derlocke_ng.kiwi.SessionViewModel

/** Join first, then the screen lock, then the app. */
@Composable
fun KiwiApp(model: SessionViewModel) {
    when (val session = model.session) {
        Session.Starting -> Surface(Modifier.fillMaxSize()) {}
        is Session.Out -> JoinScreen(session, Build.MODEL, model::join)
        is Session.Locked -> LockedScreen(session, model::unlock, model::asked, model::forget)
        is Session.In -> Fleet(session, model)
    }
}

@Composable
private fun Fleet(session: Session.In, model: SessionViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        // a machine's page belongs to Machines
                        selected = current?.route?.substringBefore('/') == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
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
                composable(Destination.Home.route) { HomeScreen(model.seen, model::refresh, toMachine) }
                composable(Destination.Services.route) { ServicesScreen(model.seen, model::refresh) }
                composable(Destination.Machines.route) { MachinesScreen(model.seen, model::refresh, toMachine) }
                composable("${Destination.Machines.route}/{name}") { entry ->
                    MachineScreen(model.seen, entry.arguments?.getString("name").orEmpty(), model::refresh)
                }
                composable(Destination.More.route) { MoreScreen(session, model::can, model::setLock, model::lock) }
            }
        }
    }
}
