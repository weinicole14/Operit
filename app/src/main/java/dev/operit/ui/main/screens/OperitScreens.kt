package dev.operit.ui.main.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.operit.ui.features.browser.screens.BrowserScreen
import dev.operit.ui.features.logcat.screens.LogcatScreen
import dev.operit.ui.features.toolbox.screens.ToolboxScreen
import dev.operit.ui.main.OperitRouter

/**
 * 应用主屏幕定义
 */
sealed class Screen(val route: String) {
    data object Toolbox : Screen("toolbox")
    data object Logcat : Screen("logcat")
    data object Browser : Screen("browser")
    
    @Composable
    fun Content(
        modifier: Modifier = Modifier,
        onNavigate: (Screen) -> Unit = {},
        onBack: () -> Unit = {}
    ) {
        when (this) {
            Toolbox -> ToolboxScreen(
                modifier = modifier,
                onLogcatSelected = { onNavigate(Logcat) },
                onBrowserSelected = { onNavigate(Browser) }
            )
            Logcat -> LogcatScreen(
                modifier = modifier,
                onBack = onBack
            )
            Browser -> BrowserScreen(
                onBack = onBack
            )
        }
    }
}

/**
 * 获取导航项对应的屏幕
 */
fun OperitRouter.getScreenForNavItem(navItem: dev.operit.ui.common.NavItem): Screen {
    return when (navItem) {
        dev.operit.ui.common.NavItem.Toolbox -> Screen.Toolbox
        dev.operit.ui.common.NavItem.Logcat -> Screen.Logcat
        dev.operit.ui.common.NavItem.Browser -> Screen.Browser
        else -> Screen.Toolbox
    }
}