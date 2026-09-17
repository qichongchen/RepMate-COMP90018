package com.repmate.engine

/**
 * Developer entry point: runs [SquatRepDetector] over every recording in `traces/` and
 * prints what it found against what the human says they did.
 *
 * Iterating the whole library rather than one hard-coded file means a newly recorded trace
 * can be eyeballed the moment it is dropped in, with no code change — the same property
 * the test suite relies on.
 *
 * Lives in the **test** source set, alongside the fixtures it reads. It is a developer
 * tool, not part of the app: a JVM `main` cannot run on a device, and the recordings it
 * loads are test resources. Keeping it here means none of it ships in the APK. Run it
 * from the IDE with the gutter arrow.
 */
fun main() {
    val traces = runCatching { TraceLibrary.loadAll() }
        .getOrElse { failure ->
            println("Could not load traces: ${failure.message}")
            return
        }

    if (traces.isEmpty()) {
        println("No recordings found in ${TraceLibrary.directory()?.absolutePath}")
        return
    }

    println("Found ${traces.size} recording(s)\n")
    traces.forEach { report(it) }
}

/** Prints one trace's detection report: the set window, the whole file, and rep timings. */
private fun report(trace: LabelledTrace) {
    val expectation = trace.expectation

    println("=".repeat(72))
    println("${trace.name}  (${expectation.exercise})")
    println(
        "  frames: %d   duration: %.2f s   sample rate: ~%.0f Hz"
            .format(trace.frames.size, trace.durationSeconds(), trace.sampleRateHz())
    )
    expectation.notes?.let { println("  notes:  $it") }
    println()

    // The honest number: reps found in the window where the set actually happened.
    val inSet = trace.detect(trace.setWindow())

    val passed = expectation.acceptsRepCount(inSet.size)
    val status = if (passed) "PASS" else "FAIL"
    println(
        "  [$status] set window %d-%d ms: detected %d, ground truth %s"
            .format(
                expectation.setStartMs,
                expectation.setEndMs,
                inSet.size,
                expectation.acceptedRangeDescription()
            )
    )

    // The whole file, including any phone handling at either end.
    val whole = trace.detect(trace.frames)
    val expectedWhole = expectation.fullTraceEvents?.toString() ?: "not recorded"
    println("  whole recording:      detected ${whole.size}, expected $expectedWhole")
    println()

    println("  events across the whole recording:")
    whole.forEach { rep ->
        val seconds = (rep.endMs - rep.startMs) / 1000.0
        val inWindow = rep.startMs >= expectation.setStartMs && rep.endMs <= expectation.setEndMs
        println(
            "    %2d: %6d -> %6d ms  (%.2f s)  amplitude %.2f  %s"
                .format(rep.index + 1, rep.startMs, rep.endMs, seconds, rep.amplitude, if (inWindow) "" else "<- outside the set")
        )
    }
    println()

    // Gap between one event ending and the next starting. Real reps in a set land at a
    // steady cadence, so an unusually long gap flags something that is probably not a rep
    // at all — typically the phone going into or out of a pocket.
    println("  rest between events:")
    whole.zipWithNext { previous, next ->
        println(
            "    %2d -> %2d: %.2f s"
                .format(previous.index + 1, next.index + 1, (next.startMs - previous.endMs) / 1000.0)
        )
    }
    println()
}
