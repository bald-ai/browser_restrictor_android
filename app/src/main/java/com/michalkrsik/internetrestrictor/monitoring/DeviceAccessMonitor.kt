package com.michalkrsik.internetrestrictor.monitoring

import android.app.Application
import android.os.Build
import android.security.advancedprotection.AdvancedProtectionManager
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.michalkrsik.internetrestrictor.AppContainer
import kotlinx.coroutines.launch

class DeviceAccessMonitor(
    private val application: Application,
    private val container: AppContainer,
) : DefaultLifecycleObserver {
    private var advancedProtectionManager: AdvancedProtectionManager? = null
    private var callback: AdvancedProtectionManager.Callback? = null

    override fun onStart(owner: LifecycleOwner) {
        refresh()
        if (Build.VERSION.SDK_INT >= 36) registerAdvancedProtectionCallback()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (Build.VERSION.SDK_INT >= 36) {
            unregisterAdvancedProtectionCallback()
        }
        callback = null
        advancedProtectionManager = null
    }

    fun refresh() {
        val access = container.accessChecker.read()
        container.applicationScope.launch {
            container.repository.updateDeviceAccess(
                accessibilityEnabled = access.accessibilityEnabled,
                usageAccessGranted = access.usageAccessGranted,
                supportedBrowserInstalled = access.supportedBrowserInstalled,
                advancedProtectionEnabled = access.advancedProtectionEnabled,
            )
        }
    }

    @RequiresApi(36)
    private fun registerAdvancedProtectionCallback() {
        try {
            val manager = application.getSystemService(AdvancedProtectionManager::class.java) ?: return
            val listener = AdvancedProtectionManager.Callback { refresh() }
            manager.registerAdvancedProtectionCallback(application.mainExecutor, listener)
            advancedProtectionManager = manager
            callback = listener
        } catch (_: Exception) {
            // Indeterminate results remain the generic observed accessibility state.
        }
    }

    @RequiresApi(36)
    private fun unregisterAdvancedProtectionCallback() {
        val manager = advancedProtectionManager
        val registered = callback
        if (manager != null && registered != null) {
            try {
                manager.unregisterAdvancedProtectionCallback(registered)
            } catch (_: Exception) {
                // A query failure is intentionally not presented as a positive block cause.
            }
        }
    }
}
