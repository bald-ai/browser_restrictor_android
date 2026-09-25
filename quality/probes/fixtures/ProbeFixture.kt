package fixture

inline fun choose(run: Boolean, action: () -> Int): Int = if (run) action() else -1
fun branch(value: Int): Int = if (value > 0) value else -value
fun maybe(value: String?): Int = value?.length ?: 0
fun nonLocalReturn(): Int { choose(true) { return 7 }; return 8 }
suspend fun resumed(value: Boolean): Int {
    kotlinx.coroutines.yield()
    return if (value) 1 else 2
}
fun loop(limit: Int): Int { var n = 0; while (n < limit) n++; return n }
fun constantLoops(): Int { var n = 0; while (false) n += 99; while (true) { n++; if (n == 2) break }; return n }
fun main() {
    check(constantLoops() == 2)
    val called = { branch(2) }; val unused = { branch(-3) }
    check(choose(true, called) == 2)
    check(choose(false, unused) == -1)
    check(branch(-4) == 4)
    check(maybe(null) == 0 && maybe("abc") == 3)
    check(nonLocalReturn() == 7)
    check(loop(0) == 0 && loop(3) == 3)
    kotlinx.coroutines.runBlocking { check(resumed(true) == 1 && resumed(false) == 2) }
    check(runCatching { choose(true) { error("expected") } }.exceptionOrNull()?.message == "expected")
    println("semantic checks passed")
}
