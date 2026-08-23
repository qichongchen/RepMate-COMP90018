package engine

fun main() {
    val expectedReps = 10
    val trace = syntheticSquatTrace(reps = expectedReps)

    println("Synthetic squat trace")
    println("  frames:   ${trace.size}")
    println("  duration: ${trace.last().tMillis / 1000.0} seconds")
    println()

    val detector = SquatRepDetector()
    val detected = detector.processAll(trace)

    println("Detection")
    println("  expected: $expectedReps reps")
    println("  detected: ${detected.size} reps")
    println("  result:   " + if (detected.size == expectedReps) "MATCH" else "MISMATCH")
    println("  ending phase: ${detector.phase}")
    println()

    println("Detected reps:")
    detected.forEach { rep ->
        val seconds = (rep.endMs - rep.startMs) / 1000.0
        println(
            "  rep %2d: %5d ms -> %5d ms  (%.2f s)  amplitude %.2f"
                .format(rep.index + 1, rep.startMs, rep.endMs, seconds, rep.amplitude)
        )
    }
}
