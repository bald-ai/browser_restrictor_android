package com.michalkrsik.internetrestrictor.v1

import android.content.Context
import com.michalkrsik.internetrestrictor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/** Only installation credentials live on the phone. Provider keys stay on the separate backend. */
class V1Api(context: Context, backendUrl: String = BuildConfig.V1_BACKEND_URL) {
    val baseUrl = backendUrl.trimEnd('/')
    private val preferences = context.getSharedPreferences("v1_installation", Context.MODE_PRIVATE)
    private val identityLock = Mutex()

    suspend fun bootstrap(): JSONObject = identityLock.withLock {
        val token = preferences.getString("token", null)
        val id = preferences.getString("id", null)
        if (token != null && id != null) return@withLock JSONObject().put("installationId", id).put("token", token)
        require(baseUrl.isNotBlank()) { "The v1 backend is not configured." }
        val identity = request("/installations", JSONObject(), authenticate = false)
        check(preferences.edit().putString("id", identity.getString("installationId"))
            .putString("token", identity.getString("token")).commit()) { "Could not save installation identity." }
        JSONObject().put("installationId", identity.getString("installationId")).put("token", identity.getString("token"))
    }

    suspend fun request(path: String, body: JSONObject? = null, authenticate: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val connection = connection(path, if (body == null) "GET" else "POST", authenticate)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            response(connection)
        } finally { connection.disconnect() }
    }

    suspend fun video(attemptId: String, file: File, endedAt: Long): JSONObject = withContext(Dispatchers.IO) {
        val boundary = "friction-${UUID.randomUUID()}"
        val connection = connection("/attempts/$attemptId/video", "POST", true).apply {
            readTimeout = 240_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.buffered().use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("endedAt", endedAt.toString())
                field("timeZone", java.util.TimeZone.getDefault().id)
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"video\"; filename=\"activity.mp4\"\r\nContent-Type: video/mp4\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            response(connection)
        } finally { connection.disconnect() }
    }

    private fun connection(path: String, method: String, authenticate: Boolean): HttpURLConnection {
        require(path.startsWith('/') && !path.contains(".."))
        return (URL("$baseUrl/api/v1$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 150_000
            instanceFollowRedirects = false
            if (authenticate) setRequestProperty("Authorization", "Bearer ${preferences.getString("token", "")}")
        }
    }

    private fun response(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val result = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        check(code in 200..299) { result.optString("error").ifBlank { "Connection failed ($code). Please try again." } }
        return result
    }

    fun isTrustedOrigin(url: String?): Boolean = matchesTrustedOrigin(baseUrl, url)
}

internal fun matchesTrustedOrigin(baseUrl: String, url: String?): Boolean = try {
        val trusted = URI(baseUrl)
        val candidate = URI(url.orEmpty())
        candidate.scheme == "https" && trusted.scheme == candidate.scheme &&
            trusted.host == candidate.host && trusted.port == candidate.port && candidate.userInfo == null
    } catch (_: Exception) { false }
