package com.michalkrsik.internetrestrictor.monitoring

import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.provider.Settings
import com.michalkrsik.internetrestrictor.domain.SUPPORTED_BROWSER_PACKAGES
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings and packages here belong only to Robolectric's in-memory framework. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], application = Application::class)
class DeviceAccessCheckerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val checker = DeviceAccessChecker(context)

    @Test fun onlyTheExactAccessibilityComponentCountsAsEnabled() {
        val expected = ComponentName(context, ChromeAccessibilityService::class.java).flattenToString()
        for (raw in listOf(null, "", "malformed", "other.package/.Service", expected + "Other")) {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, raw)
            assertFalse("Unexpected component match for $raw", checker.isAccessibilityEnabled())
        }
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "bad:other/.Service:$expected")
        assertTrue(checker.isAccessibilityEnabled())
    }

    @Test fun usagePermissionAndInstalledPackagesAreIndependentRequirements() {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        for (mode in listOf(AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED)) {
            shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, mode)
            assertFalse(checker.hasUsageAccess())
        }
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        assertTrue(checker.hasUsageAccess())
        assertFalse(checker.isSupportedBrowserInstalled())
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = SUPPORTED_BROWSER_PACKAGES.last()
            applicationInfo = ApplicationInfo().apply { packageName = SUPPORTED_BROWSER_PACKAGES.last() }
        })
        assertTrue(checker.isSupportedBrowserInstalled())
        val snapshot = checker.read()
        assertFalse(snapshot.accessibilityEnabled)
        assertTrue(snapshot.usageAccessGranted)
        assertTrue(snapshot.supportedBrowserInstalled)
        assertFalse(snapshot.advancedProtectionEnabled)
    }
}
