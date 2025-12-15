package com.ai.assistance.operit.ui.common.displays

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.operit.services.ServiceLifecycleOwner
import com.ai.assistance.operit.util.AppLogger

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

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var displayId: Int? = null
    private var isFullscreen: Boolean = false
    private var lastWindowX: Int = 0
    private var lastWindowY: Int = 0
    private var lastWindowWidth: Int = 0
    private var lastWindowHeight: Int = 0
    private var previewPath by mutableStateOf<String?>(null)

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
            params.width = (lastWindowWidth * 0.6f).toInt()
            params.height = (lastWindowHeight * 0.6f).toInt()
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
            params.width = (lastWindowWidth * 0.6f).toInt()
            params.height = (lastWindowHeight * 0.6f).toInt()
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
        val view = overlayView ?: return
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            if (isFullscreen) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy
                    lastWindowX = params.x
                    lastWindowY = params.y
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        AppLogger.e("VirtualDisplayOverlay", "Error moving overlay", e)
                    }
                    true
                }
                else -> false
            }
        }
    }

    @Composable
    private fun OverlayCard(id: Int, isFullscreen: Boolean, previewPath: String?) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Virtual display #$id",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { toggleFullScreen() }) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { hide() }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val bitmap = remember(previewPath) {
                            previewPath?.let { path ->
                                try {
                                    BitmapFactory.decodeFile(path)
                                } catch (e: Exception) {
                                    AppLogger.e("VirtualDisplayOverlay", "Error decoding preview image", e)
                                    null
                                }
                            }
                        }

                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = "Virtual screen preview",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
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
