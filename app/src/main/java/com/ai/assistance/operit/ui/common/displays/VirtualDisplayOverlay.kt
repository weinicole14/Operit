package com.ai.assistance.operit.ui.common.displays

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.operit.services.ServiceLifecycleOwner
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.agent.ShowerController
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random
import com.ai.assistance.operit.ui.floating.ui.ball.rememberParticleSystem

class VirtualDisplayOverlay private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: VirtualDisplayOverlay? = null

        fun getInstance(context: Context): VirtualDisplayOverlay {
            return instance ?: synchronized(this) {
                instance ?: VirtualDisplayOverlay(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun mapOffsetToRemote(offset: Offset, overlaySize: IntSize, videoSize: Pair<Int, Int>?): Pair<Int, Int>? {
        val (vw, vh) = videoSize ?: return null
        if (overlaySize.width <= 0 || overlaySize.height <= 0) return null
        val normX = (offset.x / overlaySize.width.toFloat()).coerceIn(0f, 1f)
        val normY = (offset.y / overlaySize.height.toFloat()).coerceIn(0f, 1f)
        val devX = (normX * (vw - 1)).roundToInt()
        val devY = (normY * (vh - 1)).roundToInt()
        return devX to devY
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var displayId: Int? = null
    private var isFullscreen by mutableStateOf(false)
    private var isSnapped by mutableStateOf(false)
    private var animator: android.animation.ValueAnimator? = null
    private var lastWindowX: Int = 0
    private var lastWindowY: Int = 0
    private var lastWindowWidth: Int = 0
    private var lastWindowHeight: Int = 0
    private var previewPath by mutableStateOf<String?>(null)
    private var controlsVisible by mutableStateOf(false)
    private var rainbowBorderVisible by mutableStateOf(false)

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post {
                try {
                    action()
                } catch (e: Exception) {
                    AppLogger.e("VirtualDisplayOverlay", "Error on main thread", e)
                }
            }
        }
    }

    private fun ensureOverlay() {
        if (overlayView != null) return
        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
            }
            layoutParams = params
            lifecycleOwner = ServiceLifecycleOwner().apply {
                handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                handleLifecycleEvent(Lifecycle.Event.ON_START)
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            overlayView = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    val id = displayId
                    val fullscreen = isFullscreen
                    val path = previewPath
                    if (id != null) {
                        OverlayCard(id = id, isFullscreen = fullscreen, previewPath = path)
                    }
                }
            }
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            AppLogger.e("VirtualDisplayOverlay", "Error creating overlay", e)
            overlayView = null
            lifecycleOwner = null
            windowManager = null
            layoutParams = null
        }
    }

    fun show(displayId: Int) {
        runOnMainThread {
            this.displayId = displayId
            isFullscreen = false
            isSnapped = false
            ensureOverlay()
            overlayView?.visibility = View.VISIBLE
            updateLayoutParams()
        }
    }

    fun hide() {
        runOnMainThread {
            try {
                ShowerController.shutdown()
                overlayView?.let { view ->
                    lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                    try {
                        windowManager?.removeView(view)
                    } catch (e: Exception) {
                        AppLogger.e("VirtualDisplayOverlay", "Error removing overlay view", e)
                    }
                }
                overlayView = null
                lifecycleOwner = null
                layoutParams = null
                windowManager = null
                displayId = null
            } catch (e: Exception) {
                AppLogger.e("VirtualDisplayOverlay", "Error hiding overlay", e)
            }
        }
    }

    fun setShowerBorderVisible(visible: Boolean) {
        runOnMainThread {
            rainbowBorderVisible = visible
        }
    }

    private fun toggleFullScreen() {
        runOnMainThread {
            isFullscreen = !isFullscreen
            if (isFullscreen) {
                controlsVisible = false
            }
            isSnapped = false
            updateLayoutParams()
        }
    }

    private fun snapToEdge(forceSnap: Boolean = false) {
        // 现在 snapToEdge 仅用于将小窗缩小为悬浮球，不再“吸附”到屏幕边缘
        runOnMainThread {
            if (!isSnapped) {
                isSnapped = true
                val params = layoutParams ?: return@runOnMainThread
                // 在当前位置缩放成圆形小球
                animateToPosition(params.x, params.y, isSnapping = true)
            }
        }
    }

    private fun animateToDefaultPosition() {
        animateToPosition(lastWindowX, lastWindowY, isSnapping = false)
    }

    private fun animateToPosition(targetX: Int, targetY: Int, isSnapping: Boolean) {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val startX = params.x
        val startY = params.y
        val startWidth = params.width
        val startHeight = params.height
        val metrics = context.resources.displayMetrics
        val snappedSize = (60 * metrics.density).toInt()
        val (endWidth, endHeight) = if (isSnapping) {
            snappedSize to snappedSize
        } else {
            (lastWindowWidth * 0.4f).toInt() to (lastWindowHeight * 0.4f).toInt()
        }
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                params.x = (startX + (targetX - startX) * fraction).toInt()
                params.y = (startY + (targetY - startY) * fraction).toInt()
                params.width = (startWidth + (endWidth - startWidth) * fraction).toInt()
                params.height = (startHeight + (endHeight - startHeight) * fraction).toInt()
                try {
                    wm.updateViewLayout(view, params)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            start()
        }
    }

    private fun updateLayoutParams() {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val metrics = context.resources.displayMetrics
        val statusBarHeight = getStatusBarHeight()
        if (lastWindowWidth == 0 || lastWindowHeight == 0) {
            lastWindowWidth = metrics.widthPixels
            lastWindowHeight = (metrics.heightPixels - statusBarHeight).coerceAtLeast(1)
            params.width = (lastWindowWidth * 0.4f).toInt()
            params.height = (lastWindowHeight * 0.4f).toInt()
            params.x = ((metrics.widthPixels - params.width) / 2f).toInt()
            params.y = statusBarHeight + ((lastWindowHeight - params.height) / 2f).toInt()
            lastWindowX = params.x
            lastWindowY = params.y
        }
        if (isFullscreen) {
            params.width = metrics.widthPixels
            params.height = metrics.heightPixels
            params.x = 0
            params.y = 0
        } else {
            params.width = (lastWindowWidth * 0.4f).toInt()
            params.height = (lastWindowHeight * 0.4f).toInt()
            params.x = lastWindowX
            params.y = lastWindowY
        }
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            AppLogger.e("VirtualDisplayOverlay", "Error updating layout params", e)
        }
    }

    private fun moveWindowBy(dx: Float, dy: Float) {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = layoutParams ?: return
        if (isFullscreen) return
        // 悬浮球模式下也允许普通拖动，不再改变缩放状态
        params.x += dx.toInt()
        params.y += dy.toInt()
        lastWindowX = params.x
        lastWindowY = params.y
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            AppLogger.e("VirtualDisplayOverlay", "Error moving overlay via moveWindowBy", e)
        }
    }

    @Composable
    private fun OverlayCard(id: Int, isFullscreen: Boolean, previewPath: String?) {
        var overlaySize by remember { mutableStateOf(IntSize.Zero) }
        val snapped = isSnapped

        if (snapped) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            moveWindowBy(dragAmount.x, dragAmount.y)
                        }
                    }
                    .pointerInput(id) {
                        detectTapGestures(
                            onTap = {
                                isSnapped = false
                                updateLayoutParams()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                ShowerSiriBall(
                    onMove = { dx, dy -> moveWindowBy(dx, dy) },
                    onClick = {
                        isSnapped = false
                        updateLayoutParams()
                    }
                )
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size -> overlaySize = size }
                    .pointerInput(id, isFullscreen) {
                        if (isFullscreen) {
                            var lastPoint: Pair<Int, Int>? = null
                            detectDragGestures(
                                onDragStart = { start ->
                                    val pt = mapOffsetToRemote(start, overlaySize, ShowerController.getVideoSize())
                                    if (pt != null) {
                                        lastPoint = pt
                                        kotlinx.coroutines.runBlocking { ShowerController.touchDown(pt.first, pt.second) }
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val pt = mapOffsetToRemote(change.position, overlaySize, ShowerController.getVideoSize())
                                    if (pt != null && pt != lastPoint) {
                                        lastPoint = pt
                                        kotlinx.coroutines.runBlocking { ShowerController.touchMove(pt.first, pt.second) }
                                    }
                                },
                                onDragEnd = {
                                    lastPoint?.let { pt ->
                                        kotlinx.coroutines.runBlocking { ShowerController.touchUp(pt.first, pt.second) }
                                    }
                                    lastPoint = null
                                },
                                onDragCancel = {
                                    lastPoint?.let { pt ->
                                        kotlinx.coroutines.runBlocking { ShowerController.touchUp(pt.first, pt.second) }
                                    }
                                    lastPoint = null
                                }
                            )
                        } else {
                             detectDragGestures(
                                onDragStart = { controlsVisible = true },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    moveWindowBy(dragAmount.x, dragAmount.y)
                                }
                            )
                        }
                    }
                    .pointerInput(id, isFullscreen, overlaySize) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (isFullscreen) {
                                    val pt = mapOffsetToRemote(offset, overlaySize, ShowerController.getVideoSize())
                                    if (pt != null) {
                                        kotlinx.coroutines.runBlocking {
                                            ShowerController.touchDown(pt.first, pt.second)
                                            ShowerController.touchUp(pt.first, pt.second)
                                        }
                                    }
                                } else {
                                    controlsVisible = true
                                }
                            }
                        )
                    },
                shape = RoundedCornerShape(0.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                        var hasShowerDisplay by remember { mutableStateOf(ShowerController.getVideoSize() != null) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                val ready = ShowerController.getVideoSize() != null
                                if (hasShowerDisplay != ready) {
                                    hasShowerDisplay = ready
                                }
                                delay(500)
                            }
                        }
                        if (id == 0 && hasShowerDisplay) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx -> ShowerSurfaceView(ctx) }
                            )
                            LaunchedEffect(controlsVisible) {
                                if (controlsVisible) {
                                    delay(3000)
                                    controlsVisible = false
                                }
                            }
                            if (controlsVisible && !isFullscreen) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                                )
                            }
                            if (rainbowBorderVisible) {
                                RainbowStatusBorderOverlay()
                            }
                            if (isFullscreen) {
                                // Fullscreen: top-right Windows-like controls, small white icons on pill background
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(Color.Black.copy(alpha = 0.45f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        // Minimize (映射到贴边，类似最小化到侧边)
                                        IconButton(
                                            onClick = {
                                                // 全屏 -> 悬浮球：仅缩小为圆形小球，不做边缘吸附
                                                toggleFullScreen()
                                                snapToEdge()
                                            },
                                            modifier = Modifier.size(32.dp),
                                            colors = IconButtonDefaults.iconButtonColors(
                                                contentColor = Color.White,
                                                containerColor = Color.Transparent
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Minimize,
                                                contentDescription = "Minimize",
                                                modifier = Modifier.size(18.dp),
                                                tint = Color.White
                                            )
                                        }
                                        // Restore (退出全屏)
                                        IconButton(
                                            onClick = { toggleFullScreen() },
                                            modifier = Modifier.size(32.dp),
                                            colors = IconButtonDefaults.iconButtonColors(
                                                contentColor = Color.White,
                                                containerColor = Color.Transparent
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.FullscreenExit,
                                                contentDescription = "Exit Fullscreen",
                                                modifier = Modifier.size(18.dp),
                                                tint = Color.White
                                            )
                                        }
                                        // Close
                                        IconButton(
                                            onClick = { hide() },
                                            modifier = Modifier.size(32.dp),
                                            colors = IconButtonDefaults.iconButtonColors(
                                                contentColor = Color.White,
                                                containerColor = Color.Transparent
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Close",
                                                modifier = Modifier.size(18.dp),
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            } else if (controlsVisible) {
                                // Small window: Centered, vertical column
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(onClick = { snapToEdge() }) {
                                        Icon(imageVector = Icons.Outlined.Minimize, contentDescription = "Minimize to ball", modifier = Modifier.size(32.dp))
                                    }
                                    IconButton(onClick = { toggleFullScreen() }) {
                                        Icon(imageVector = Icons.Filled.Fullscreen, contentDescription = "Toggle Fullscreen", modifier = Modifier.size(32.dp))
                                    }
                                    IconButton(onClick = { hide() }) {
                                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(32.dp))
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Shower 虚拟屏尚未就绪",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
            }
        }
    }

private fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }
}

@Composable
private fun RainbowStatusBorderOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "vd_status_indicator_rainbow")
    val animatedProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "vd_status_indicator_progress"
    )

    val rainbowColors = listOf(
        Color(0xFFFF5F6D),
        Color(0xFFFFC371),
        Color(0xFF47CF73),
        Color(0xFF00C6FF),
        Color(0xFF845EF7),
        Color(0xFFFF5F6D)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.025f
            val innerCornerRadius = androidx.compose.ui.geometry.CornerRadius(0f, 0f)

            val phase = animatedProgress * size.maxDimension
            val borderBrush = Brush.linearGradient(
                colors = rainbowColors,
                start = Offset(-phase, 0f),
                end = Offset(size.width - phase, size.height)
            )

            val innerRoundRect = androidx.compose.ui.geometry.RoundRect(
                left = strokeWidth,
                top = strokeWidth,
                right = size.width - strokeWidth,
                bottom = size.height - strokeWidth,
                cornerRadius = innerCornerRadius
            )
            val innerPath = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(innerRoundRect)
            }

            val outerPath = androidx.compose.ui.graphics.Path().apply {
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
            }
            val ringPath = androidx.compose.ui.graphics.Path().apply {
                op(outerPath, innerPath, androidx.compose.ui.graphics.PathOperation.Difference)
            }

            clipPath(innerPath) {
                val bandSteps = 5
                val innerBandWidth = strokeWidth * 3f
                val singleBandWidth = innerBandWidth / bandSteps
                val maxAlpha = 0.32f

                for (i in 0 until bandSteps) {
                    val t = i / (bandSteps - 1).coerceAtLeast(1).toFloat()
                    val alpha = (1f - t) * maxAlpha

                    val inset = i * singleBandWidth + singleBandWidth / 2f

                    val bandLeft = innerRoundRect.left + inset
                    val bandTop = innerRoundRect.top + inset
                    val bandRight = innerRoundRect.right - inset
                    val bandBottom = innerRoundRect.bottom - inset
                    if (bandRight <= bandLeft || bandBottom <= bandTop) break

                    val bandCornerRadius = androidx.compose.ui.geometry.CornerRadius(0f, 0f)

                    drawRoundRect(
                        brush = borderBrush,
                        topLeft = Offset(bandLeft, bandTop),
                        size = Size(bandRight - bandLeft, bandBottom - bandTop),
                        cornerRadius = bandCornerRadius,
                        style = Stroke(width = singleBandWidth),
                        alpha = alpha
                    )
                }
            }

            drawPath(
                path = ringPath,
                brush = borderBrush,
                alpha = 0.7f
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// 精简版 SiriBall：仅保留视觉与手势（拖动 + 点击），不依赖 FloatContext / 语音 / AI 状态
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun ShowerSiriBall(
    onMove: (Float, Float) -> Unit,
    onClick: () -> Unit
) {
    // Siri 配色
    val mainColor = Color(0xFF00FFFF) // Cyan
    val accentColor1 = Color(0xFFFF00FF) // Magenta
    val accentColor2 = Color(0xFFF07B3F) // Orange
    val accentColor3 = Color(0xFF00FF00) // Lime

    // 按压状态（仅用于视觉缩放）
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1.0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "vd_pressScale"
    )

    // 无限动画：旋转 / 呼吸 / 外圈波纹
    val infiniteTransition = rememberInfiniteTransition(label = "vd_siri")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vd_rotation"
    )

    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "vd_breathe"
    )

    val ripple1Scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vd_ripple1Scale"
    )
    val ripple1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vd_ripple1Alpha"
    )

    val ripple2Scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(833)
        ),
        label = "vd_ripple2Scale"
    )
    val ripple2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(833)
        ),
        label = "vd_ripple2Alpha"
    )

    val ripple3Scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1666)
        ),
        label = "vd_ripple3Scale"
    )
    val ripple3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1666)
        ),
        label = "vd_ripple3Alpha"
    )

    // 粒子系统：沿用 SiriBall 的 BallParticles，实现 3D 轨迹
    val particleSystem = rememberParticleSystem()
    particleSystem.UpdateEffect(isPressed)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isPressed = true
                    },
                    onDragEnd = {
                        isPressed = false
                    },
                    onDragCancel = {
                        isPressed = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onMove(dragAmount.x, dragAmount.y)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 2f) * pressScale

            // 0. 后景粒子
            with(particleSystem) {
                drawBackParticles(center, baseRadius * 0.5f)
            }

            // 1. 外圈音波（三层）
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        mainColor.copy(alpha = ripple3Alpha * 0.15f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * ripple3Scale
                ),
                center = center,
                radius = baseRadius * ripple3Scale
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        accentColor1.copy(alpha = ripple2Alpha * 0.2f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * ripple2Scale
                ),
                center = center,
                radius = baseRadius * ripple2Scale
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        accentColor3.copy(alpha = ripple1Alpha * 0.25f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * ripple1Scale
                ),
                center = center,
                radius = baseRadius * ripple1Scale
            )

            // 2. 底部光晕
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        mainColor.copy(alpha = 0.3f),
                        accentColor1.copy(alpha = 0.2f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * breathe * 0.7f
                ),
                center = center,
                radius = baseRadius * breathe * 0.7f,
                blendMode = BlendMode.Screen
            )

            // 3. 流动彩色光斑（4 个）
            fun drawColorBlob(
                angleDeg: Float,
                distance: Float,
                color: Color,
                sizeFactor: Float
            ) {
                val rad = (rotation + angleDeg) * PI.toFloat() / 180f
                val blobCenter = Offset(
                    center.x + distance * cos(rad),
                    center.y + distance * sin(rad)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = 0.8f),
                            color.copy(alpha = 0.5f),
                            color.copy(alpha = 0.2f),
                            Color.Transparent
                        ),
                        center = blobCenter,
                        radius = baseRadius * sizeFactor
                    ),
                    center = blobCenter,
                    radius = baseRadius * sizeFactor,
                    blendMode = BlendMode.Screen
                )
            }

            drawColorBlob(0f, baseRadius * 0.2f * breathe, mainColor, 0.7f)
            drawColorBlob(90f, baseRadius * 0.25f * breathe, accentColor1, 0.65f)
            drawColorBlob(180f, baseRadius * 0.22f * breathe, accentColor2, 0.6f)
            drawColorBlob(270f, baseRadius * 0.23f * breathe, accentColor3, 0.68f)

            // 4. 中心核心高光
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.7f),
                        mainColor.copy(alpha = 0.6f),
                        accentColor1.copy(alpha = 0.5f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 0.65f * breathe
                ),
                center = center,
                radius = baseRadius * 0.65f * breathe,
                blendMode = BlendMode.Screen
            )

            // 5. 玻璃高光
            val highlightCenter = Offset(
                center.x - baseRadius * 0.25f,
                center.y - baseRadius * 0.25f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.5f),
                        Color.White.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = highlightCenter,
                    radius = baseRadius * 0.35f
                ),
                center = highlightCenter,
                radius = baseRadius * 0.35f,
                blendMode = BlendMode.Screen
            )

            // 6. 软边界
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * breathe
                ),
                center = center,
                radius = baseRadius * breathe
            )

            // 7. 前景粒子
            with(particleSystem) {
                drawFrontParticles(center, baseRadius * 0.5f)
            }
        }
    }
}

