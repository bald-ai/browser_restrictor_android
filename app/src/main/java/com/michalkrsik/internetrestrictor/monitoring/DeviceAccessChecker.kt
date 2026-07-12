package com.michalkrsik.internetrestrictor.monitoring

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import com.michalkrsik.internetrestrictor.domain.CHROME_PACKAGE

data class DeviceAccessSnapshot(
    val accessibilityEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val chromeInstalled: Boolean,
    val advancedProtectionEnabled: Boolean,
)

class DeviceAccessChecker(private val context: Context) {
    fun read(): DeviceAccessSnapshot = DeviceAccessSnapshot(
        accessibilityEnabled = isAccessibilityEnabled(),
        usageAccessGranted = hasUsageAccess(),
        chromeInstalled = isChromeInstalled(),
        advancedProtectionEnabled = isAdvancedProtectionEnabled(),
    )

    fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(context, ChromeAccessibilityService::class.java)
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return raw.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isChromeInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                CHROME_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(CHROME_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isAdvancedProtectionEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return try {
            context.getSystemService(AdvancedProtectionManager::class.java)
                ?.isAdvancedProtectionEnabled == true
        } catch (_: Exception) {
            false
        }
    }
}

