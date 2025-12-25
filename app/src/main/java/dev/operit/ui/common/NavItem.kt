package dev.operit.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import dev.operit.R

/**
 * 导航项定义
 */
sealed class NavItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    data object Toolbox : NavItem(
        route = "toolbox",
        labelRes = R.string.nav_toolbox,
        icon = Icons.Default.Build
    )
    
    data object Logcat : NavItem(
        route = "logcat",
        labelRes = R.string.nav_logcat,
        icon = Icons.Default.ListAlt
    )
    
    data object Browser : NavItem(
        route = "browser",
        labelRes = R.string.nav_browser,
        icon = Icons.Default.Public
    )
}