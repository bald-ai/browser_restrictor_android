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
import com.michalkrsik.internetrestrictor.ui.gate.GateActions
import com.michalkrsik.internetrestrictor.ui.gate.GateScreen
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class GateOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val repository: RestrictorRepository,
    private val scope: CoroutineScope,
    private val onLeaveChrome: () -> Unit,
    private val onFocusChanged: (Boolean) -> Unit,
    private val onAttachFailure: (Throwable) -> Unit,
) {
    private var view: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var focusListener: android.view.ViewTreeObserver.OnWindowFocusChangeListener? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val owner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Internet Restrictor Chrome pause screen"
            owner.attachTo(this)
            setContent {
                val snapshot by repository.snapshot.collectAsState()
                RestrictorTheme(snapshot.settings.theme) {
                    GateScreen(
                        snapshot = snapshot,
                        actions = GateActions(
                            onStartSession = { minutes ->
                                scope.launch { repository.startWait(snapshot.stateRevision, minutes) }
                            },
                            onEmergencySkip = {
                                scope.launch {
                                    repository.emergencySkip(
                                        snapshot.stateRevision,
                                        snapshot.pendingWaitId,
                                    )
                                }
                            },
                            onTryAgain = {
                                scope.launch {
                                    repository.tryAgain(
                                        snapshot.stateRevision,
                                        snapshot.gateSessionId,
                                        snapshot.outOfTimeLatchToken,
                                    )
                                }
                            },
                            onLeaveChrome = onLeaveChrome,
                        ),
                    )
                }
            }
        }
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            onFocusChanged(focused)
        }
        composeView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        try {
            windowManager.addView(composeView, gateLayoutParams())
            view = composeView
            lifecycleOwner = owner
            focusListener = listener
        } catch (error: Throwable) {
            owner.destroy()
            composeView.disposeComposition()
            onAttachFailure(error)
        }
    }

    fun hide(reportFocusLoss: Boolean = true) {
        val current = view ?: return
        focusListener?.let { listener ->
            if (current.viewTreeObserver.isAlive) {
                current.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
            }
        }
        try {
            windowManager.removeViewImmediate(current)
        } catch (_: IllegalArgumentException) {
            // Already removed by the platform.
        }
        current.disposeComposition()
        lifecycleOwner?.destroy()
        view = null
        lifecycleOwner = null
        focusListener = null
        if (reportFocusLoss) onFocusChanged(false)
    }

    fun recreateForConfigurationChange() {
        if (!isShowing) return
        hide(reportFocusLoss = false)
        show()
    }

    private fun gateLayoutParams() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.FILL
        title = "Internet Restrictor Chrome gate"
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }
}
