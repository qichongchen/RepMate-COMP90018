package com.example.repmate

import com.repmate.engine.BurstDetector
import com.repmate.engine.ExerciseType
import com.repmate.engine.TraceLibrary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TEMPORARY - delete with SensorProbeActivity.
 *
 * Checks the probe's diagnostic shadow against the real detectors on every recorded trace.
 *
 * The shadow exists because the detectors cannot report *why* a window was discarded: it
 * re-runs the same trigger with the guards switched off and re-applies them itself. That is
 * only useful if it reaches the same verdicts the detector does — otherwise a live log would
 * blame the wrong guard, and a threshold would get changed on bad evidence.
 *
 * So: replay each trace exactly the way the probe's collector does, and require the windows
 * the shadow calls ACCEPT to be precisely the reps the detector emitted.
 */
class ProbeShadowTest {

    @Test
    fun `the probe's shadow reaches the same verdicts as the real detectors`() {
        val traces = TraceLibrary.loadAll()
        val failures = mutableListOf<String>()

        for (trace in traces) {
            val probe = when (trace.expectation.exercise) {
                ExerciseType.SQUAT -> ProbeDetector.Squat()
                ExerciseType.JUMPING_JACK -> ProbeDetector.JumpingJack()
                ExerciseType.PUSHUP -> continue
            }
            val shadow = BurstDetector()

            var accepted = 0
            var emitted = 0
            var disagreements = 0

            // Mirrors SensorProbeActivity's collector: one frame into both, then compare.
            for (frame in trace.frames) {
                val rep = probe.process(frame)
                if (rep != null) emitted++

                val burst = shadow.process(frame) ?: continue
                val verdict = probe.explain(burst)
                val shadowAccepted = verdict.startsWith("ACCEPT")
                if (shadowAccepted) accepted++
                // The detector emits on the same frame the window closes, so the two must
                // agree frame by frame, not merely in total.
                if (shadowAccepted != (rep != null)) disagreements++
            }

            if (accepted != emitted || disagreements != 0) {
                failures += "  ${trace.name} [${trace.expectation.exercise}]: " +
                    "detector emitted $emitted, shadow accepted $accepted, " +
                    "$disagreements frame-level disagreement(s)"
            }
        }

        assertEquals(
            emptyList(), failures.toList(),
            "the probe's diagnostic shadow disagrees with the real detectors:\n" +
                failures.joinToString("\n"),
        )
    }
}
