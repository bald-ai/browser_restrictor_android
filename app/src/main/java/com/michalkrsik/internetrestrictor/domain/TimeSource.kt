package com.michalkrsik.internetrestrictor.domain

import android.os.SystemClock
import java.time.ZoneId

interface TimeSource {
    fun wallTimeMs(): Long
    fun elapsedRealtimeMs(): Long
    fun zoneId(): ZoneId
}

class AndroidTimeSource : TimeSource {
    override fun wallTimeMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}

class FakeTimeSource(
    var wallMs: Long,
    var elapsedMs: Long = 0,
    var zone: ZoneId = ZoneId.of("UTC"),
) : TimeSource {
    override fun wallTimeMs(): Long = wallMs
    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun zoneId(): ZoneId = zone

    fun advance(ms: Long) {
        require(ms >= 0)
        wallMs += ms
        elapsedMs += ms
    }
}

