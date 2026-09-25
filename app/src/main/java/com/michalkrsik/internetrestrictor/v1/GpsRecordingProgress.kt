package com.michalkrsik.internetrestrictor.v1

/** Live projection of retained evidence. Invalid evidence must never look rewardable. */
class GpsRecordingProgress(mode: String?) {
    private val distance = GpsDistanceTracker(mode)
    private var lastTime: Long? = null
    var failure: String? = null
        private set

    val meters: Double
        get() = if (failure == null) distance.meters else 0.0

    fun add(point: GpsPoint, mock: Boolean) {
        if (failure != null) return
        if (mock) {
            failure = "Mock GPS detected. This recording cannot earn rewards."
            return
        }
        val previous = lastTime
        if (previous != null && point.time <= previous) {
            failure = "GPS timestamps went backwards or repeated. This recording cannot earn rewards."
            return
        }
        lastTime = point.time
        distance.add(point)
    }
}
