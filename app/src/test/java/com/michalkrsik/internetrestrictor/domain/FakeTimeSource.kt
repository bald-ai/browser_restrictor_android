package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId

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

