package com.michalkrsik.internetrestrictor.v1

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.webkit.*
import com.michalkrsik.internetrestrictor.AppContainer
import com.michalkrsik.internetrestrictor.data.ConfigurationJson
import com.michalkrsik.internetrestrictor.domain.AppConfiguration
import com.michalkrsik.internetrestrictor.fixtures.FixtureApplication
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import javax.net.ssl.HttpsURLConnection

/** WebView is Robolectric's non-rendering shadow; HTTP is exclusively a loopback TLS fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FixtureApplication::class)
class V1ConversationActivityTest {
    private val app = RuntimeEnvironment.getApplication() as FixtureApplication
    private val server = MockWebServer()
    private val originalTls = HttpsURLConnection.getDefaultSSLSocketFactory()
    private lateinit var controller: ActivityController<V1ConversationActivity>
    private lateinit var activity: V1ConversationActivity
    private lateinit var view: WebView
    private val config = AppConfiguration(revision = 1)
    private var failAttempt = false
    private val origin get() = server.url("/").toString().trimEnd('/')

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        HttpsURLConnection.setDefaultSSLSocketFactory(HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build().sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = when (request.path) {
                    "/api/v1/installations" -> JSONObject().put("installationId", "fixture").put("token", "fixture-token")
                    "/api/v1/config" -> JSONObject().put("revision", 1).put("appliedRevision", 1).put("config", ConfigurationJson.configurationJson(config))
                    "/api/v1/statistics", "/api/v1/ack" -> JSONObject()
                    "/api/v1/attempts" -> {
                        if (failAttempt) return MockResponse().setResponseCode(503).setBody("{\"error\":\"Fixture upload unavailable\"}")
                        val body = JSONObject(request.body.readUtf8())
                        body.put("preview", true).put("task", ConfigurationJson.taskJson(config.tasks.first { it.id == body.getString("taskId") }))
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(response.toString()).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
        val old = app.container
        app.container = AppContainer(app, app.fixtureScope, app.clock, repository = old.repository,
            v1 = V1Coordinator(app, old.repository, app.fixtureScope, V1Api(app, origin), app.clock::wallTimeMs))
        controller = Robolectric.buildActivity(V1ConversationActivity::class.java).create().start().resume()
        activity = controller.get()
        await { ReflectionHelpers.getField<WebView?>(activity, "webView") != null }
        view = ReflectionHelpers.getField(activity, "webView")
    }
    @After fun cleanup() {
        if (::controller.isInitialized && (!::view.isInitialized || !shadowOf(view).wasDestroyCalled())) controller.pause().stop().destroy()
        app.fixtureScope.cancel(); server.shutdown()
        HttpsURLConnection.setDefaultSSLSocketFactory(originalTls)
    }
    private fun await(condition: () -> Boolean) {
        repeat(600) { shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(5) }
        fail("Local activity operation did not finish")
    }
    private fun bridge() = shadowOf(view).getJavascriptInterface("FrictionNative")
    private fun testTask(id: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(bridge(), "testTask", from(String::class.java, "proposal"), from(Int::class.javaPrimitiveType, 1), from(String::class.java, id))
    }
    private class Permission(private val requestedOrigin: String, private vararg val requested: String) : PermissionRequest() {
        var granted: List<String>? = null
        var denied = 0
        override fun getOrigin(): Uri = Uri.parse(requestedOrigin)
        override fun getResources(): Array<String> = requested.toList().toTypedArray()
        override fun grant(resources: Array<out String>) { granted = resources.toList() }
        override fun deny() { denied++ }
    }
    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = mutableMapOf<String, String>()
    }

    @Test fun unavailableBootstrapShowsRetryWithoutLeavingAPartiallyTrustedWebView() {
        controller.pause().stop().destroy()
        app.getSharedPreferences("v1_installation", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val old = app.container
        app.container = AppContainer(app, app.fixtureScope, app.clock, repository = old.repository,
            v1 = V1Coordinator(app, old.repository, app.fixtureScope, V1Api(app, ""), app.clock::wallTimeMs))
        controller = Robolectric.buildActivity(V1ConversationActivity::class.java).create().start().resume()
        activity = controller.get()
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.LinearLayout
        val status = root.getChildAt(1) as android.widget.TextView
        await { status.text.contains("Tap here to retry.") }
        assertTrue(status.text.contains("not configured"))
        assertNull(ReflectionHelpers.getField<WebView?>(activity, "webView"))
        assertEquals(2, root.childCount)
        controller.pause().stop().destroy()
    }

    @Test fun trustedOriginAndIsolatedWebSettingsGuardTheNativeBridgeAndLifecycle() {
        assertEquals("$origin/", shadowOf(view).lastLoadedUrl)
        assertTrue(view.settings.javaScriptEnabled); assertTrue(view.settings.domStorageEnabled)
        assertFalse(view.settings.allowFileAccess); assertFalse(view.settings.allowContentAccess)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.settings.mixedContentMode)
        val client = shadowOf(view).webViewClient
        assertFalse(client.shouldOverrideUrlLoading(view, request("$origin/review")))
        assertTrue(client.shouldOverrideUrlLoading(view, request("https://untrusted.invalid/")))
        assertTrue(client.shouldOverrideUrlLoading(view, null as WebResourceRequest?))
        val identity = JSONObject(ReflectionHelpers.callInstanceMethod<String>(bridge(), "bootstrap"))
        assertEquals("fixture", identity.getString("installationId"))
        ReflectionHelpers.callInstanceMethod<Unit>(bridge(), "configChanged")
        controller.pause()
        assertEquals("window.dispatchEvent(new Event('pagehide'));", shadowOf(view).lastEvaluatedJavascript)
        controller.resume()
        ReflectionHelpers.callInstanceMethod<Unit>(bridge(), "close")
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
        assertTrue(shadowOf(view).wasDestroyCalled())
        assertNull(shadowOf(view).getJavascriptInterface("FrictionNative"))
        assertEquals("about:blank", shadowOf(view).lastLoadedUrl)
    }

    @Test fun microphonePermissionChecksOriginResourceAndNativeGrantBeforeAllowingOnlyAudio() {
        val client = shadowOf(view).webChromeClient
        val foreign = Permission("https://untrusted.invalid", PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val cameraOnly = Permission(origin, PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        client.onPermissionRequest(foreign); client.onPermissionRequest(cameraOnly)
        assertEquals(1, foreign.denied); assertEquals(1, cameraOnly.denied)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val allowed = Permission(origin, PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        client.onPermissionRequest(allowed)
        assertEquals(listOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), allowed.granted)
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val first = Permission(origin, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val second = Permission(origin, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        client.onPermissionRequest(first)
        assertNull(first.granted)
        val callbacks = ReflectionHelpers.getField<Map<String, Any>>(activity.activityResultRegistry, "keyToCallback")
        val permissionResult = ReflectionHelpers.getField<androidx.activity.result.ActivityResultCallback<Boolean>>(callbacks.values.single(), "callback")
        // Robolectric resolves its permission prompt immediately. Restore a pending request
        // to model the asynchronous Android callback and cancellation boundaries explicitly.
        ReflectionHelpers.setField(activity, "microphoneRequest", first)
        val deniedBeforeReplacement = first.denied
        client.onPermissionRequest(second)
        assertEquals(deniedBeforeReplacement + 1, first.denied)
        ReflectionHelpers.setField(activity, "microphoneRequest", second)
        val priorScript = shadowOf(view).lastEvaluatedJavascript
        controller.pause()
        assertEquals(priorScript, shadowOf(view).lastEvaluatedJavascript)
        controller.resume()
        val deniedBeforeResult = second.denied
        permissionResult.onActivityResult(false)
        assertEquals(deniedBeforeResult + 1, second.denied)
        val granted = Permission(origin, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        ReflectionHelpers.setField(activity, "microphoneRequest", granted)
        permissionResult.onActivityResult(true)
        assertEquals(listOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), granted.granted)
        val foreignResult = Permission("https://untrusted.invalid", PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        ReflectionHelpers.setField(activity, "microphoneRequest", foreignResult)
        permissionResult.onActivityResult(true)
        assertEquals(1, foreignResult.denied)
        val cancelled = Permission(origin, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        ReflectionHelpers.setField(activity, "microphoneRequest", cancelled)
        client.onPermissionRequestCanceled(first)
        assertSame(cancelled, ReflectionHelpers.getField<PermissionRequest?>(activity, "microphoneRequest"))
        client.onPermissionRequestCanceled(cancelled)
        permissionResult.onActivityResult(true)
        assertNull(cancelled.granted)
        ReflectionHelpers.setField(activity, "microphoneRequest", first)
        val deniedBeforeDestroy = first.denied
        controller.pause().stop().destroy()
        assertEquals(deniedBeforeDestroy + 1, first.denied)
    }

    @Test fun taskPreviewLaunchesTheCorrectRecorderAndReportsFailuresWithoutDuplicatingRequests() {
        for (task in listOf(config.tasks.first { it.verifier == "gps" }, config.tasks.first { it.verifier == "video" })) {
            testTask(task.id)
            await { !ReflectionHelpers.getField<Boolean>(activity, "previewOpening") }
            val intent = shadowOf(activity).nextStartedActivity
            assertNotNull(intent)
            assertEquals(task.id, intent.getStringExtra("taskId"))
            assertNotNull(intent.getStringExtra("previewAttemptId"))
            assertTrue(intent.component!!.className.endsWith(if (task.verifier == "gps") "V1GpsActivity" else "V1VideoActivity"))
        }
        ReflectionHelpers.setField(activity, "previewOpening", true)
        testTask(config.tasks.first().id)
        assertNull(shadowOf(activity).nextStartedActivity)
        ReflectionHelpers.setField(activity, "previewOpening", false)
        failAttempt = true
        testTask(config.tasks.first().id)
        await { !ReflectionHelpers.getField<Boolean>(activity, "previewOpening") }
        assertTrue(shadowOf(view).lastEvaluatedJavascript.contains("Fixture upload unavailable"))
        view.loadUrl("https://untrusted.invalid")
        testTask(config.tasks.first().id)
        await { !ReflectionHelpers.getField<Boolean>(activity, "previewOpening") }
        assertTrue(shadowOf(view).lastEvaluatedJavascript.contains("Task testing is available from your Friction setup."))
    }
}
