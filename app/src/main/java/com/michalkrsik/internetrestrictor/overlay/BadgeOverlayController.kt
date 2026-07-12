package com.michalkrsik.internetrestrictor.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.michalkrsik.internetrestrictor.data.RestrictorRepository
import com.michalkrsik.internetrestrictor.ui.gate.BadgeContent
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme

class BadgeOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val repository: RestrictorRepository,
    private val onAttachFailure: (Throwable) -> Unit,
) {
    private var view: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val owner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            owner.attachTo(this)
            setContent {
                val snapshot by repository.snapshot.collectAsState()
                RestrictorTheme(snapshot.settings.theme) { BadgeContent(snapshot) }
            }
        }
        try {
            windowManager.addView(composeView, badgeLayoutParams())
            view = composeView
            lifecycleOwner = owner
        } catch (error: Throwable) {
            owner.destroy()
            composeView.disposeComposition()
            onAttachFailure(error)
        }
    }

    fun hide() {
        val current = view ?: return
        try {
            windowManager.removeViewImmediate(current)
        } catch (_: IllegalArgumentException) {
            // Already removed by the platform.
        }
        current.disposeComposition()
        lifecycleOwner?.destroy()
        view = null
        lifecycleOwner = null
    }

    fun recreateForConfigurationChange() {
        if (!isShowing) return
        hide()
        show()
    }

    private fun badgeLayoutParams() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 48.dp
        title = "Internet Restrictor Chrome status"
    }

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()
}

