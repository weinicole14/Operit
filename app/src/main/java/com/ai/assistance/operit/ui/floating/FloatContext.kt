package com.ai.assistance.operit.ui.floating

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.services.FloatingChatService
import com.ai.assistance.operit.services.floating.FloatingWindowState
import com.ai.assistance.operit.ui.floating.ui.window.models.ResizeEdge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 简化后的FloatContext类，移除了路由耦合逻辑 */
@Composable
fun rememberFloatContext(
        messages: List<ChatMessage>,
        width: Dp,
        height: Dp,
        onClose: () -> Unit,
        onResize: (Dp, Dp) -> Unit,
        ballSize: Dp = 48.dp,
        windowScale: Float = 1.0f,
        onScaleChange: (Float) -> Unit,
        currentMode: FloatingMode,
        previousMode: FloatingMode = FloatingMode.WINDOW,
        onModeChange: (FloatingMode) -> Unit,
        onMove: (Float, Float, Float) -> Unit = { _, _, _ -> },
        snapToEdge: (Boolean) -> Unit = { _ -> },
        isAtEdge: Boolean = false,
        screenWidth: Dp = 1080.dp,
        screenHeight: Dp = 2340.dp,
        currentX: Float = 0f,
        currentY: Float = 0f,
        saveWindowState: (() -> Unit)? = null,
        onSendMessage: ((String, PromptFunctionType) -> Unit)? = null,
        onCancelMessage: (() -> Unit)? = null,
        onAttachmentRequest: ((String) -> Unit)? = null,
        attachments: List<AttachmentInfo> = emptyList(),
        onRemoveAttachment: ((String) -> Unit)? = null,
        onInputFocusRequest: ((Boolean) -> Unit)? = null,
        chatService: FloatingChatService? = null,
        windowState: FloatingWindowState? = null
): FloatContext {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 只在真正需要重新创建 Context 时才通过 remember 的 key 触发
    // 对于频繁变化的数据（如 messages, 坐标等），使用 SideEffect 更新
    val floatContext = remember(
            // 回调函数通常是稳定的或者我们希望它们更新时Context重建（如果它们捕获了外部状态）
            // 但为了最大程度避免重建，我们可以只依赖那些“架构性”的参数
            onClose,
            onResize,
            onScaleChange,
            onModeChange,
            onMove,
            snapToEdge,
            saveWindowState,
            onSendMessage,
            onCancelMessage,
            onAttachmentRequest,
            onRemoveAttachment,
            onInputFocusRequest,
            chatService,
            windowState
    ) {
        FloatContext(
                initialMessages = messages,
                initialWidth = width,
                initialHeight = height,
                onClose = onClose,
                onResize = onResize,
                ballSize = ballSize,
                initialWindowScale = windowScale,
                onScaleChange = onScaleChange,
                initialMode = currentMode,
                initialPreviousMode = previousMode,
                onModeChange = onModeChange,
                onMove = onMove,
                snapToEdge = snapToEdge,
                initialIsAtEdge = isAtEdge,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                initialX = currentX,
                initialY = currentY,
                saveWindowState = saveWindowState,
                onSendMessage = onSendMessage,
                onCancelMessage = onCancelMessage,
                onAttachmentRequest = onAttachmentRequest,
                initialAttachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                onInputFocusRequest = onInputFocusRequest,
                density = density,
                coroutineScope = scope,
                chatService = chatService,
                windowState = windowState
        )
    }

    // 使用 SideEffect 更新频繁变化的状态
    SideEffect {
        floatContext.messages = messages
        floatContext.windowWidthState = width
        floatContext.windowHeightState = height
        floatContext.windowScale = windowScale
        floatContext.currentMode = currentMode
        floatContext.previousMode = previousMode
        floatContext.isAtEdge = isAtEdge
        floatContext.currentX = currentX
        floatContext.currentY = currentY
        floatContext.attachments = attachments
    }

    return floatContext
}

/** 简化的悬浮窗状态与回调上下文 */
class FloatContext(
        initialMessages: List<ChatMessage>,
        initialWidth: Dp,
        initialHeight: Dp,
        val onClose: () -> Unit,
        val onResize: (Dp, Dp) -> Unit,
        val ballSize: Dp,
        initialWindowScale: Float,
        val onScaleChange: (Float) -> Unit,
        initialMode: FloatingMode,
        initialPreviousMode: FloatingMode,
        val onModeChange: (FloatingMode) -> Unit,
        val onMove: (Float, Float, Float) -> Unit,
        val snapToEdge: (Boolean) -> Unit,
        initialIsAtEdge: Boolean,
        val screenWidth: Dp,
        val screenHeight: Dp,
        initialX: Float,
        initialY: Float,
        val saveWindowState: (() -> Unit)?,
        val onSendMessage: ((String, PromptFunctionType) -> Unit)?,
        val onCancelMessage: (() -> Unit)?,
        val onAttachmentRequest: ((String) -> Unit)?,
        initialAttachments: List<AttachmentInfo>,
        val onRemoveAttachment: ((String) -> Unit)?,
        val onInputFocusRequest: ((Boolean) -> Unit)?,
        val density: Density,
        val coroutineScope: CoroutineScope,
        val chatService: FloatingChatService? = null,
        val windowState: FloatingWindowState? = null
) {
    // 使用 mutableStateOf 让 Compose 能感知变化
    var messages by mutableStateOf(initialMessages)
    var windowWidthState by mutableStateOf(initialWidth)
    var windowHeightState by mutableStateOf(initialHeight)
    var windowScale by mutableStateOf(initialWindowScale)
    var currentMode by mutableStateOf(initialMode)
    var previousMode by mutableStateOf(initialPreviousMode)
    var isAtEdge by mutableStateOf(initialIsAtEdge)
    var currentX by mutableStateOf(initialX)
    var currentY by mutableStateOf(initialY)
    var attachments by mutableStateOf(initialAttachments)

    // 动画与转换相关状态
    val animatedAlpha = Animatable(1f)
    val transitionFeedback = Animatable(0f)

    // 大小调整相关状态
    var isEdgeResizing: Boolean = false
    var activeEdge: ResizeEdge = ResizeEdge.NONE
    var initialWindowWidth: Float = 0f
    var initialWindowHeight: Float = 0f

    // 对话框与内容显示状态
    var showInputDialog: Boolean by mutableStateOf(false)
    var userMessage: String by mutableStateOf("")
    var contentVisible: Boolean by mutableStateOf(true)
    var showAttachmentPanel: Boolean by mutableStateOf(false)
}
