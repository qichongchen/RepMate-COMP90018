package engine

import java.io.File

/**
 * Candidate locations for the recorded trace, tried in order.
 *
 * The IDE runs `main` from the project root, but a terminal run may start elsewhere, and
 * the file currently lives at the repo root rather than in `traces/`. Checking both keeps
 * the demo runnable either way.
 */
private val TRACE_CANDIDATES = listOf(
    "traces/squat_10_pocket.csv",
    "squat_10_pocket.csv"
)

fun main() {
    val tracePath = TRACE_CANDIDATES.firstOrNull { File(it).isFile }
    if (tracePath == null) {
        println("Could not find the recorded trace. Looked in:")
        TRACE_CANDIDATES.forEach { println("  ${File(it).absolutePath}") }
        return
    }

    val trace = loadSensorLoggerCsv(tracePath)
    if (trace.isEmpty()) {
        println("Loaded $tracePath but it contained no usable rows.")
        return
    }

    val durationSeconds = trace.last().tMillis / 1000.0
    val sampleRateHz = trace.size / durationSeconds

    println("Real squat trace: $tracePath")
    println("  frames:      ${trace.size}")
    println("  duration:    %.2f s".format(durationSeconds))
    println("  sample rate: ~%.0f Hz".format(sampleRateHz))
    println()

    // The human performed 10 squats; roughly 9 were expected to be cleanly detectable.
    val actualSquats = 10
    val detector = SquatRepDetector()
    val detected = detector.processAll(trace)

    println("Detection (acceleration magnitude, 25-sample moving average)")
    println("  squats performed: $actualSquats")
    println("  reps detected:    ${detected.size}")
    println("  ending phase:     ${detector.phase}")
    println()

    println("Detected reps:")
    detected.forEach { rep ->
        val seconds = (rep.endMs - rep.startMs) / 1000.0
        println(
            "  rep %2d: %6d ms -> %6d ms  (%.2f s)  amplitude %.2f"
                .format(rep.index + 1, rep.startMs, rep.endMs, seconds, rep.amplitude)
        )
    }
    println()

    // Gap between one rep ending and the next starting. Real squats in a set land at a
    // steady cadence, so an unusually long gap flags a detection that is probably not a
    // squat at all (e.g. the phone going into or out of the pocket).
    println("Rest between reps:")
    detected.zipWithNext { previous, next ->
        println("  rep %2d -> %2d: %.2f s".format(previous.index + 1, next.index + 1, (next.startMs - previous.endMs) / 1000.0))
    }
}
