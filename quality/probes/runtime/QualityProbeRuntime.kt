package friction.quality.runtime

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Compiled only for measurement builds. Each sandbox appends its own observed probe IDs. */
private object Observations {
    val seen = ConcurrentHashMap.newKeySet<String>()
    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            System.getProperty("quality.probe.events")?.let { path ->
                synchronized(System.getProperties()) {
                    File(path).appendText(seen.sorted().joinToString("\n", postfix = "\n"))
                }
            }
        })
    }
}
fun probeHit(id: String) { Observations.seen.add(id) }
fun probeCondition(id: String, value: Boolean): Boolean {
    probeHit("$id:$value")
    return value
}
