package dev.operit.ui.features.toolbox.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.operit.R
import dev.operit.ui.common.CustomScaffold

/**
 * 工具类别
 */
enum class ToolCategory(val displayNameRes: Int) {
    SYSTEM(R.string.tool_category_system),
    NETWORK(R.string.tool_category_network),
    MEDIA(R.string.tool_category_media),
    INTERNET(R.string.tool_category_internet)
}

/**
 * 工具项数据类
 */
data class Tool(
    val id: String,
    val nameRes: Int,
    val icon: @Composable () -> Unit,
    val descriptionRes: Int,
    val category: ToolCategory,
    val onClick: () -> Unit
)

/**
 * 工具箱主屏幕
 */
@Composable
fun ToolboxScreen(
    modifier: Modifier = Modifier,
    onLogcatSelected: () -> Unit = {},
    onBrowserSelected: () -> Unit = {}
) {
    val tools = listOf(
        Tool(
            id = "logcat",
            nameRes = R.string.tool_logcat,
            icon = { Icon(Icons.Default.ListAlt, contentDescription = null) },
            descriptionRes = R.string.tool_logcat_desc,
            category = ToolCategory.SYSTEM,
            onClick = onLogcatSelected
        ),
        Tool(
            id = "browser",
            nameRes = R.string.tool_browser,
            icon = { Icon(Icons.Default.Public, contentDescription = null) },
            descriptionRes = R.string.tool_browser_desc,
            category = ToolCategory.INTERNET,
            onClick = onBrowserSelected
        )
    )

    CustomScaffold(
        title = stringResource(R.string.nav_toolbox),
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 按类别分组显示工具
            ToolCategory.values().forEach { category ->
                val categoryTools = tools.filter { it.category == category }
                if (categoryTools.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(category.displayNameRes),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(categoryTools) { tool ->
                        ToolItem(tool = tool)
                    }
                }
            }
        }
    }
}

/**
 * 工具项组件
 */
@Composable
private fun ToolItem(
    tool: Tool,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        onClick = tool.onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                tool.icon()
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(tool.nameRes),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(tool.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 浏览器工具的标准界面包装
 */
@Composable
fun BrowserToolScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    BrowserScreen(onBack = onBack)
}