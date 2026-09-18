package com.michalkrsik.internetrestrictor.v1

import org.json.JSONObject
import java.io.File

enum class PreviewRecordingStatus { READY, WAITING_UPLOAD, COMPLETE, INTERRUPTED, MISSING }

/** Durable ownership of a preview recording ID. A started ID can only submit its original evidence. */
internal class PreviewRecordingStore(private val directory: File, private val evidenceDirectory: File) {
    private val lock = Any()
    private fun valid(id: String) = Regex("[a-zA-Z0-9_-]{1,128}").matches(id)
    fun read(id: String?): JSONObject? {
        if (id == null || !valid(id)) return null
        return runCatching { JSONObject(File(directory, "$id.json").readText()).takeIf { it.optBoolean("preview") } }.getOrNull()
    }
    fun result(id: String?): JSONObject? {
        if (id == null || !valid(id)) return null
        return runCatching { JSONObject(File(directory, "$id-result.json").readText()) }.getOrNull()
    }
    fun status(id: String?): PreviewRecordingStatus {
        val attempt = read(id) ?: return PreviewRecordingStatus.MISSING
        if (result(id) != null) return PreviewRecordingStatus.COMPLETE
        if (File(evidenceDirectory, "$id.json").exists()) return PreviewRecordingStatus.WAITING_UPLOAD
        return if (attempt.optBoolean("captureStarted")) PreviewRecordingStatus.INTERRUPTED else PreviewRecordingStatus.READY
    }
    fun claim(id: String): JSONObject = synchronized(lock) {
        check(status(id) == PreviewRecordingStatus.READY) { "This test recording has already started. Review its result or retry its saved upload." }
        val attempt = read(id)!!.put("captureStarted", true)
        val temporary = File(directory, "$id.tmp")
        temporary.writeText(attempt.toString())
        check(temporary.renameTo(File(directory, "$id.json"))) { "The test recording could not be saved." }
        attempt
    }
}
