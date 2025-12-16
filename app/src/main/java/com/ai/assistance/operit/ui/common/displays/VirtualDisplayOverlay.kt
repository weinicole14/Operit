package com.ai.assistance.operit.ui.common.displays

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.ai.assistance.operit.ui.common.displays.ShowerSurfaceView
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

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
    private var lastWindowX: Int = 0
    private var lastWindowY: Int = 0
    private var lastWindowWidth: Int = 0
    private var lastWindowHeight: Int = 0
    private var previewPath by mutableStateOf<String?>(null)
    private var controlsVisible by mutableStateOf(false)

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
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
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

            attachDragListener()
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
            ensureOverlay()
            overlayView?.visibility = View.VISIBLE
            updateLayoutParams()
        }
    }

    fun updatePreview(imagePath: String) {
        runOnMainThread {
            previewPath = imagePath
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

    private fun toggleFullScreen() {
        runOnMainThread {
            isFullscreen = !isFullscreen
            if (isFullscreen) {
                // When entering fullscreen, ensure grey overlay is not visible.
                controlsVisible = false
            }
            updateLayoutParams()
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
            params.height = (metrics.heightPixels - statusBarHeight).coerceAtLeast(1)
            params.x = 0
            params.y = statusBarHeight
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

    private fun attachDragListener() {
        // Drag and tap gestures are now handled in Compose (OverlayCard) via pointerInput.
        // This method is kept as a no-op to avoid interfering with child views.
    }

    private fun moveWindowBy(dx: Float, dy: Float) {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = layoutParams ?: return
        if (isFullscreen) return

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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .onSizeChanged { size ->
                    overlaySize = size
                }
                // Handle drag over the entire video area.
                .pointerInput(id, isFullscreen, overlaySize) {
                    if (isFullscreen) {
                        var lastPoint: Pair<Int, Int>? = null
                        detectDragGestures(
                            onDragStart = { start ->
                                // In fullscreen we do not use controlsVisible for grey overlay;
                                // keep it unchanged to avoid blocking input.
                                val videoSize = ShowerController.getVideoSize()
                                val pt = mapOffsetToRemote(start, overlaySize, videoSize)
                                if (pt != null) {
                                    kotlinx.coroutines.runBlocking {
                                        ShowerController.touchDown(pt.first, pt.second)
                                    }
                                }
                                lastPoint = pt
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val videoSize = ShowerController.getVideoSize()
                                val pt = mapOffsetToRemote(change.position, overlaySize, videoSize)
                                if (pt != null && pt != lastPoint) {
                                    kotlinx.coroutines.runBlocking {
                                        ShowerController.touchMove(pt.first, pt.second)
                                    }
                                    lastPoint = pt
                                }
                            },
                            onDragEnd = {
                                val pt = lastPoint
                                if (pt != null) {
                                    kotlinx.coroutines.runBlocking {
                                        ShowerController.touchUp(pt.first, pt.second)
                                    }
                                }
                                lastPoint = null
                            },
                            onDragCancel = {
                                lastPoint = null
                            }
                        )
                    } else {
                        // Small-window mode: drag moves the floating window itself.
                        detectDragGestures(
                            onDragStart = {
                                controlsVisible = true
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                moveWindowBy(dragAmount.x, dragAmount.y)
                            }
                        )
                    }
                }
                // Handle tap separately so that single taps work in both modes.
                .pointerInput(id, isFullscreen, overlaySize) {
                    detectTapGestures(
                        onTap = { offset ->
                            // Only small-window mode uses controlsVisible to show grey overlay.
                            if (!isFullscreen) {
                                controlsVisible = true
                            }
                            if (isFullscreen) {
                                val videoSize = ShowerController.getVideoSize()
                                val pt = mapOffsetToRemote(offset, overlaySize, videoSize)
                                if (pt != null) {
                                    kotlinx.coroutines.runBlocking {
                                        // Map a simple tap to down+up at the same point, like the Python client
                                        ShowerController.touchDown(pt.first, pt.second)
                                        ShowerController.touchUp(pt.first, pt.second)
                                    }
                                }
                            }
                        }
                    )
                },
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
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { ctx ->
                            ShowerSurfaceView(ctx)
                        }
                    )

                    // Controls overlay: tap (detected via root drag listener) will set controlsVisible.
                    LaunchedEffect(controlsVisible) {
                        if (controlsVisible) {
                            delay(3000)
                            controlsVisible = false
                        }
                    }

                    // In small-window mode: show grey background + button when controlsVisible.
                    // In fullscreen mode: always show only the button at top-right, without grey overlay.
                    if (controlsVisible && !isFullscreen) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                                )
                        )
                    }

                    if (isFullscreen || controlsVisible) {
                        IconButton(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            onClick = { toggleFullScreen() }
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
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

    private fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }
}
