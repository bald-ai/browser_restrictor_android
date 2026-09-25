package com.michalkrsik.internetrestrictor.v1

import android.app.Application
import android.content.Context
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/** JVM-only Android preferences and local HTTP contracts. No activity or device is launched. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class V1ApiContractTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var api: V1Api
    private data class Request(val path: String, val method: String, val auth: String?, val type: String?, val body: String)
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private var status = 200
    private var response = "{\"ok\":true}"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("v1_installation", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += Request(request.path!!, request.method!!,
                    request.getHeader("Authorization"), request.getHeader("Content-Type"), request.body.readUtf8())
                val body = if (request.path!!.endsWith("/installations"))
                    "{\"installationId\":\"installation-fixture\",\"token\":\"token-fixture\"}"
                    else response
                return MockResponse().setResponseCode(status).setBody(body)
            }
        }
        server.start()
        api = V1Api(context, server.url("/").toString())
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun concurrentBootstrapRegistersOnceAndRestartsReuseSavedIdentity() = runBlocking {
        val identities = List(3) { async { api.bootstrap() } }.awaitAll()
        assertTrue(identities.all { it.getString("token") == "token-fixture" })
        assertEquals(1, requests.size)
        assertEquals("POST", requests.single().method)
        assertNull(requests.single().auth)
        assertEquals("{}", requests.single().body)
        val restarted = V1Api(context, api.baseUrl)
        assertEquals("installation-fixture", restarted.bootstrap().getString("installationId"))
        assertEquals(1, requests.size)
    }

    @Test fun authenticatedRequestsPreserveMethodsBodiesAndServerErrors() = runBlocking {
        api.bootstrap()
        assertTrue(api.request("/state").getBoolean("ok"))
        assertEquals("GET", requests.last().method)
        assertEquals("Bearer token-fixture", requests.last().auth)
        api.request("/context", JSONObject().put("selectedCardId", "setting:waitSeconds"))
        assertEquals("POST", requests.last().method)
        assertEquals("application/json", requests.last().type)
        assertEquals("setting:waitSeconds", JSONObject(requests.last().body).getString("selectedCardId"))
        status = 409; response = "{\"error\":\"Review changed\"}"
        val failure = runCatching { api.request("/state") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Review changed", failure!!.message)
        response = "not JSON"
        assertTrue(runCatching { api.request("/state") }.exceptionOrNull()!!.message!!.contains("409"))
        status = 204; response = ""
        assertEquals(0, api.request("/state").length())
    }

    @Test fun videoSubmissionCarriesIdentityEvidenceAndPinnedEndTime() = runBlocking {
        api.bootstrap()
        val file = File(context.cacheDir, "fixture-video.mp4").apply { writeText("fixture-media-bytes") }
        assertTrue(api.video("attempt-fixture", file, 123456L).getBoolean("ok"))
        val request = requests.last()
        assertEquals("/api/v1/attempts/attempt-fixture/video", request.path)
        assertEquals("Bearer token-fixture", request.auth)
        assertTrue(request.type!!.startsWith("multipart/form-data; boundary=friction-"))
        assertTrue(request.body.contains("name=\"endedAt\"\r\n\r\n123456"))
        assertTrue(request.body.contains("name=\"timeZone\""))
        assertTrue(request.body.contains("filename=\"activity.mp4\""))
        assertTrue(request.body.contains("fixture-media-bytes"))
        assertTrue(request.body.endsWith("--\r\n"))
        assertTrue(file.delete())
    }

    @Test fun missingConfigurationAndUnsafePathsFailBeforeNetworkContact() = runBlocking {
        assertTrue(runCatching { V1Api(context, "").bootstrap() }.exceptionOrNull() is IllegalArgumentException)
        for (path in listOf("state", "/../state")) {
            assertTrue(runCatching { api.request(path) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, requests.size)
        assertFalse(api.isTrustedOrigin(api.baseUrl))
    }
}
