package io.github.zr987459.pigo.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.zr987459.pigo.AppGraph
import io.github.zr987459.pigo.feature.chat.ChatPlaceholder
import io.github.zr987459.pigo.feature.onboarding.OnboardingPlaceholder
import io.github.zr987459.pigo.feature.sessions.SessionsPlaceholder
import io.github.zr987459.pigo.feature.settings.SettingsPlaceholder

/** 四个顶层目的地。MVP 期间 feature 只提供页面，导航只在 :app。 */
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Onboarding("onboarding", "安装", Icons.Filled.Home),
    Chat("chat", "聊天", Icons.Filled.Email),
    Sessions("sessions", "会话", Icons.Filled.List),
    Settings("settings", "设置", Icons.Filled.Settings),
}

@Composable
fun PiGoNavHost(graph: AppGraph, navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = current == d.route,
                        onClick = { navController.navigateTopLevel(d) },
                        icon = { Icon(d.icon, contentDescription = null) },
                        label = { Text(d.label) },
                        modifier = Modifier.testTag("nav:${d.route}"),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Destination.Onboarding.route, modifier = Modifier.padding(padding)) {
            composable(Destination.Onboarding.route) {
                OnboardingPlaceholder(graph.runtime, onDone = { navController.navigateTopLevel(Destination.Chat) })
            }
            composable(Destination.Chat.route) { ChatPlaceholder(graph.engine) }
            composable(Destination.Sessions.route) { SessionsPlaceholder(graph.sessions) }
            composable(Destination.Settings.route) { SettingsPlaceholder(graph.providerConfig) }
        }
    }
}

/** 顶层切换：回到起点再进入，不叠加返回栈；保存/恢复各页状态。 */
fun NavHostController.navigateTopLevel(d: Destination) {
    navigate(d.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
