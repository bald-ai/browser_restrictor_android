package com.michalkrsik.internetrestrictor.monitoring

/**
 * Keeps a completed Leave action fail-open until Chrome has genuinely been left.
 *
 * This prevents the Accessibility event caused by detaching the gate from immediately
 * showing the gate again over the same Chrome window.
 */
class LeaveRearmGuard {
    var suppressingOverlays: Boolean = false
        private set

    private var observedNonChrome = false

    fun beginSuppression() {
        suppressingOverlays = true
        observedNonChrome = false
    }

    /**
     * Returns true only when a later Chrome entry is allowed to re-arm overlays.
     */
    fun observe(chromeActive: Boolean): Boolean {
        if (!suppressingOverlays) return false
        if (!chromeActive) {
            observedNonChrome = true
            return false
        }
        if (!observedNonChrome) return false
        suppressingOverlays = false
        observedNonChrome = false
        return true
    }

    fun reset() {
        suppressingOverlays = false
        observedNonChrome = false
    }
}
