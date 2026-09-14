package com.michalkrsik.internetrestrictor.ui.challenge

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal suspend fun callVerificationServer(
    videoFile: File,
    verifyUrl: String,
    sharedSecret: String,
): ExerciseVerificationResult {
    repeat(7) { attempt ->
        val boundary = "browser-restrictor-${System.nanoTime()}"
        val prefix = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"video\"; filename=\"challenge.mp4\"\r\n" +
            "Content-Type: video/mp4\r\n\r\n").toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val connection = (URL(verificationUrl(verifyUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 240_000 // Server: probe 20s + encode 20s + model 180s, plus margin.
            doOutput = true
            setFixedLengthStreamingMode(prefix.size.toLong() + videoFile.length() + suffix.size)
            setRequestProperty("Authorization", "Bearer $sharedSecret")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        val response = cancellableExchange(connection) {
            connection.outputStream.buffered().use { output ->
                output.write(prefix)
                videoFile.inputStream().buffered().use { it.copyTo(output) }
                output.write(suffix)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            HttpVerificationResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
                connection.getHeaderField("Retry-After"))
        }
        if (response.code == 503 && attempt < 6) {
            kotlinx.coroutines.delay(retryAfterDelayMs(response.retryAfter))
        } else {
            check(response.code in 200..299) { "Verification HTTP ${response.code}" }
            return parseExerciseVerificationResult(response.body)
        }
    }
    error("Verifier is busy. Try checking again later.")
}

private data class HttpVerificationResponse(val code: Int, val body: String, val retryAfter: String?)

private suspend fun <T> cancellableExchange(connection: HttpURLConnection, block: () -> T): T =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { connection.disconnect() }
        kotlinx.coroutines.Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
            try {
                if (continuation.isActive) continuation.resumeWith(Result.success(block()))
            } catch (error: Exception) {
                continuation.resumeWith(Result.failure(error))
            } finally {
                connection.disconnect()
            }
        })
    }

internal fun retryAfterDelayMs(header: String?, nowMs: Long = System.currentTimeMillis()): Long {
    val seconds = header?.toLongOrNull()
    if (seconds != null && seconds >= 0) return seconds.coerceAtMost(86_400L) * 1_000L
    return try {
        (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs)
            .coerceIn(0L, 86_400_000L)
    } catch (_: Exception) { 5_000L }
}
