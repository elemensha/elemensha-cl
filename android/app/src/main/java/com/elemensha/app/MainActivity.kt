package com.elemensha.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elemensha.app.ui.*

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "대시보드", Icons.Default.Dashboard),
    Config("config", "설정", Icons.Default.Tune),
    Logs("logs", "로그", Icons.Default.Article),
    More("more", "더보기", Icons.Default.MoreHoriz),
}

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ElemenshaTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                if (!state.paired) {
                    PairScreen(
                        connecting = state.connecting,
                        error = state.error,
                        savedUrl = vm.savedServerUrl,
                        onPair = vm::pair,
                        onDismissError = vm::dismissError,
                    )
                } else {
                    MainScaffold(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(vm: AppViewModel) {
    val nav = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.dismissError()
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("elemensha") },
                actions = {
                    ConnectionDot(connected = state.connected)
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val selected = current?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Dashboard.route) { DashboardScreen(vm, state) }
            composable(Tab.Config.route) { ConfigScreen(vm, state) }
            composable(Tab.Logs.route) { LogScreen(state) }
            composable(Tab.More.route) {
                MoreScreen(
                    vm = vm,
                    state = state,
                    onOpenCredentials = { nav.navigate("credentials") },
                    onOpenUpdate = { nav.navigate("update") },
                )
            }
            composable("credentials") { CredentialsScreen(vm, state, onBack = { nav.popBackStack() }) }
            composable("update") { UpdateScreen(vm, state, onBack = { nav.popBackStack() }) }
        }
    }
}
