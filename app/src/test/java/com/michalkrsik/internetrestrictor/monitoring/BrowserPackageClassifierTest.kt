package com.michalkrsik.internetrestrictor.monitoring

import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.SUPPORTED_BROWSERS
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
}
