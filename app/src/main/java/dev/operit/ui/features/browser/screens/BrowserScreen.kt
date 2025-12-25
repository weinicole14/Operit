package dev.operit.ui.features.browser.screens

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.operit.R
import dev.operit.ui.common.CustomScaffold
import dev.operit.ui.common.WebViewConfig
import dev.operit.ui.common.rememberWebViewState

/**
 * 浏览器主屏幕
 */
@Composable
fun BrowserScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val webViewState = rememberWebViewState("https://www.google.com")
    var urlInput by remember { mutableStateOf(webViewState.lastLoadedUrl) }
    var isUrlBarFocused by remember { mutableStateOf(false) }

    val webView = remember {
        WebViewConfig.createWebView(context).apply {
            webViewClient = WebViewConfig.createWebViewClient { url ->
                // 处理外部协议链接（如支付宝、微信等）
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    false // WebView处理
                } else {
                    // 尝试用外部应用打开
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            webChromeClient = WebViewConfig.createWebChromeClient()
        }
    }

    LaunchedEffect(webViewState.lastLoadedUrl) {
        urlInput = webViewState.lastLoadedUrl
    }

    CustomScaffold(
        title = stringResource(R.string.tool_browser),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 地址栏和导航按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 后退按钮
                IconButton(
                    onClick = {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        }
                    },
                    enabled = webView.canGoBack()
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.browser_back)
                    )
                }

                // 前进按钮
                IconButton(
                    onClick = {
                        if (webView.canGoForward()) {
                            webView.goForward()
                        }
                    },
                    enabled = webView.canGoForward()
                ) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = stringResource(R.string.browser_forward)
                    )
                }

                // 地址输入框
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(stringResource(R.string.browser_url_hint))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            isUrlBarFocused = false
                            val url = if (!urlInput.startsWith("http://") && !urlInput.startsWith("https://")) {
                                "https://" + urlInput
                            } else {
                                urlInput
                            }
                            webView.loadUrl(url)
                        }
                    ),
                    trailingIcon = {
                        if (urlInput.isNotEmpty()) {
                            IconButton(
                                onClick = { urlInput = "" }
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "清除")
                            }
                        }
                    }
                )

                // 刷新按钮
                IconButton(
                    onClick = { webView.reload() }
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.browser_refresh)
                    )
                }

                // 主页按钮
                IconButton(
                    onClick = {
                        webView.loadUrl("https://www.google.com")
                    }
                ) {
                    Icon(
                        Icons.Default.Home,
                        contentDescription = stringResource(R.string.browser_home)
                    )
                }
            }

            // WebView显示区域
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize()
                )

                // 加载指示器
                if (webViewState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(48.dp)
                    )
                }

                // 错误状态
                if (webViewState.lastError != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.browser_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}