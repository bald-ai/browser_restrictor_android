package com.michalkrsik.internetrestrictor.monitoring

import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.ProtectedTarget
import com.michalkrsik.internetrestrictor.domain.SUPPORTED_BROWSERS
import com.michalkrsik.internetrestrictor.domain.X_PACKAGE_NAME
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_PACKAGE_NAME
import com.michalkrsik.internetrestrictor.domain.protectedTargetForPackage
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserPackageClassifierTest {
    private val restrictorPackage = "com.michalkrsik.browserrestrictor"

    @Test
    fun allSupportedBrowserPackagesUseTheSharedBrowserClass() {
        assertEquals(
            listOf(
                "com.android.chrome",
                "org.mozilla.firefox",
                "com.microsoft.emmx",
                "com.opera.browser",
                X_PACKAGE_NAME,
            ),
            SUPPORTED_BROWSERS.map { it.packageName },
        )
        SUPPORTED_BROWSERS.forEach { browser ->
            assertEquals(
                browser.name,
                ForegroundClass.BROWSER,
                classifyForegroundPackage(browser.packageName, restrictorPackage),
            )
        }
    }

    @Test
    fun xUsesTheSharedBrowserPoolPolicy() {
        assertEquals(
            ForegroundClass.BROWSER,
            classifyForegroundPackage(X_PACKAGE_NAME, restrictorPackage),
        )
        assertEquals(
            ProtectedTarget.BROWSER_POOL,
            protectedTargetForPackage(X_PACKAGE_NAME),
        )
    }

    @Test
    fun restrictorAndOtherAppsAreNotClassifiedAsBrowsers() {
        assertEquals(
            ForegroundClass.RESTRICTOR,
            classifyForegroundPackage(restrictorPackage, restrictorPackage),
        )
        assertEquals(
            ForegroundClass.OTHER,
            classifyForegroundPackage("com.android.settings", restrictorPackage),
        )
    }

    @Test
    fun youtubeIsASeparateProtectedTargetRatherThanPartOfTheBrowserPool() {
        assertEquals(
            ProtectedTarget.YOUTUBE,
            protectedTargetForPackage(YOUTUBE_PACKAGE_NAME),
        )
        assertEquals(
            ForegroundClass.OTHER,
            classifyForegroundPackage(YOUTUBE_PACKAGE_NAME, restrictorPackage),
        )
        assertEquals(
            ProtectedTarget.BROWSER_POOL,
            protectedTargetForPackage("com.android.chrome"),
        )
        assertEquals(null, protectedTargetForPackage("com.google.android.apps.youtube.music"))
    }
}
