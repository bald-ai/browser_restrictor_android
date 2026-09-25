package com.michalkrsik.internetrestrictor.v1

import kotlin.math.*

data class GpsPoint(val latitude: Double, val longitude: Double, val time: Long, val accuracy: Double, val speed: Double?)

/** Must stay identical to `verifyGps` in backend-v1/evidence.mjs so live distance equals the verified result. */
class GpsDistanceTracker(private val mode: String?) {
    private var anchor: GpsPoint? = null
    var meters = 0.0
        private set

    fun add(p: GpsPoint) {
        if (p.accuracy < 0 || p.accuracy > 25) { anchor = null; return }
        val a = anchor ?: run { anchor = p; return }
        val seconds = (p.time - a.time) / 1000.0
        if (seconds <= 0) return
        if (seconds > 15) { anchor = p; return }
        val metres = distance(a, p)
        if (!qualifyingMotion(metres / seconds, p.speed)) { anchor = p; return }
        if (metres < max(2.0, (a.accuracy + p.accuracy) * .35)) return
        meters += metres
        anchor = p
    }

    private fun qualifyingMotion(inferred: Double, deviceSpeed: Double?): Boolean {
        val measured = deviceSpeed?.takeIf { it.isFinite() && it >= 0 }
        val band = speedBand()
        if (inferred > band.maximum || (measured ?: inferred) > band.maximum) return false
        return inferred >= band.minimumPosition && (measured ?: band.minimumDevice) >= band.minimumDevice
    }

    private fun speedBand(): SpeedBand = when (mode) {
        "walk" -> SpeedBand(0.0, .5, 5.0)
        "sprint" -> SpeedBand(15 / 3.6, 15 / 3.6, 35 / 3.6)
        else -> SpeedBand(0.0, 0.0, 35 / 3.6)
    }

    private class SpeedBand(val minimumPosition: Double, val minimumDevice: Double, val maximum: Double)

    companion object {
        private fun rad(x: Double) = x * PI / 180
        fun distance(a: GpsPoint, b: GpsPoint): Double {
            val x = sin(rad(b.latitude - a.latitude) / 2).pow(2) +
                cos(rad(a.latitude)) * cos(rad(b.latitude)) * sin(rad(b.longitude - a.longitude) / 2).pow(2)
            return 6371000 * 2 * atan2(sqrt(x), sqrt(max(0.0, 1 - x)))
        }
    }
}
