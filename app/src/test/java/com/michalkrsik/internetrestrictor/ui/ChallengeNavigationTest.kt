package com.michalkrsik.internetrestrictor.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import com.michalkrsik.internetrestrictor.domain.SUPPORTED_BROWSERS
import com.michalkrsik.internetrestrictor.ui.challenge.openChallengeOrigin
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mocked activity records launch intent construction only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChallengeNavigationTest {
    private val activity = mock(Activity::class.java)
    private val packages = mock(PackageManager::class.java)
    private val origin = Intent()
    @Before fun setup() {
        `when`(activity.intent).thenReturn(origin)
        `when`(activity.packageManager).thenReturn(packages)
    }
    @Test fun preferredProtectedAppReceivesTheReturnIntentAndFlags() {
        origin.putExtra("return_package", "com.google.android.youtube")
        val launch = Intent("fixture.youtube")
        `when`(packages.getLaunchIntentForPackage("com.google.android.youtube")).thenReturn(launch)
        assertTrue(activity.openChallengeOrigin())
        verify(activity).startActivity(launch)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, launch.flags)
        verify(packages, times(1)).getLaunchIntentForPackage("com.google.android.youtube")
        verifyNoMoreInteractions(packages)
    }
    @Test fun untrustedOriginFallsBackToAnInstalledBrowserAndMissingAppsDoNothing() {
        origin.putExtra("return_package", "unrelated.private.app")
        assertFalse(activity.openChallengeOrigin())
        verify(activity, never()).startActivity(any())
        verify(packages, never()).getLaunchIntentForPackage("unrelated.private.app")
        val browser = SUPPORTED_BROWSERS.last().packageName
        val launch = Intent("fixture.browser")
        `when`(packages.getLaunchIntentForPackage(browser)).thenReturn(launch)
        assertTrue(activity.openChallengeOrigin())
        verify(activity).startActivity(launch)
    }
    @Test fun failedLaunchReturnsFalseWithoutTryingToOpenAnotherApp() {
        val browser = SUPPORTED_BROWSERS.first().packageName
        val launch = Intent("fixture.browser")
        origin.putExtra("return_package", browser)
        `when`(packages.getLaunchIntentForPackage(browser)).thenReturn(launch)
        doThrow(SecurityException("fixture unavailable")).`when`(activity).startActivity(launch)
        assertFalse(activity.openChallengeOrigin())
        verify(packages, times(1)).getLaunchIntentForPackage(browser)
        verify(activity, times(1)).startActivity(launch)
    }
}
