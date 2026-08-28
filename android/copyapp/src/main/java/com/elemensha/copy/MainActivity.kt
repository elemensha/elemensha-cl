package com.elemensha.copy

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
import com.elemensha.copy.ui.*

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Account("account", "계좌", Icons.Default.AccountBalanceWallet),
    Orders("orders", "주문", Icons.Default.ReceiptLong),
    Chart("chart", "잔고", Icons.Default.ShowChart),
    Settings("settings", "설정", Icons.Default.Tune),
    More("more", "더보기", Icons.Default.MoreHoriz),
}

class MainActivity : ComponentActivity() {

    private val vm: CopyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ElemenshaCopyTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                if (!state.joined) {
                    JoinScreen(
                        connecting = state.connecting,
                        error = state.error,
                        savedUrl = vm.savedServerUrl,
                        onJoin = vm::join,
                        onDismissError = vm::dismissMessage,
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
private fun MainScaffold(vm: CopyViewModel) {
    val nav = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.dismissMessage()
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("elemensha copy") },
                actions = {
                    CopyRunningDot(running = state.running)
                    Spacer(Modifier.width(10.dp))
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
            startDestination = Tab.Account.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Account.route) { AccountScreen(vm, state) }
            composable(Tab.Orders.route) { OrdersScreen(vm, state) }
            composable(Tab.Chart.route) { ChartScreen(vm, state) }
            composable(Tab.Settings.route) { SettingsScreen(vm, state) }
            composable(Tab.More.route) {
                MoreScreen(
                    vm = vm,
                    state = state,
                    onOpenCredentials = { nav.navigate("credentials") },
                    onOpenLogs = { nav.navigate("logs") },
                    onOpenUpdate = { nav.navigate("update") },
                )
            }
            composable("credentials") {
                CredentialsScreen(vm, state, onBack = { nav.popBackStack() })
            }
            composable("logs") { LogScreen(state, onBack = { nav.popBackStack() }) }
            composable("update") {
                UpdateScreen(vm, state, onBack = { nav.popBackStack() })
            }
        }
    }
}
