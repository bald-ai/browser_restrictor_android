package com.michalkrsik.internetrestrictor.v1

import org.junit.Assert.*
import org.junit.Test

class TrustedOriginTest {
    private val origin = "https://friction.example.com"

    @Test fun sameOriginPagesMayUseTheBridgeAndMicrophone() {
        assertTrue(matchesTrustedOrigin(origin, "https://friction.example.com/"))
        assertTrue(matchesTrustedOrigin(origin, "https://friction.example.com/setup?view=voice"))
    }

    @Test fun lookalikeAndExternalPagesCannotUseTheBridge() {
        listOf("https://friction.example.com.evil.test", "https://evil.test", "http://friction.example.com",
            "https://friction.example.com:8443", "https://user@friction.example.com", "file:///etc/passwd", "javascript:alert(1)", "about:blank", "invalid").forEach {
            assertFalse(it, matchesTrustedOrigin(origin, it))
        }
        assertFalse(matchesTrustedOrigin(origin, null))
    }
}
