package com.repmate.engine

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates a synthetic squat motion trace with a KNOWN number of reps, so we can
 * develop and test the detector against ground truth before we have real phone data.
 *
 * Simplified model (phone in pocket): vertical acceleration (ay) oscillates once per
 * rep around gravity (~9.8 m/s^2) — like one sine cycle per squat, dipping as you go
 * down and rising as you come up. Small random noise and minor motion on the other
 * axes make it realistic. Deterministic given [seed], so tests are repeatable.
 *
 * @param reps how many squats to simulate (this is the ground-truth count)
 * @param samplesPerRep samples in one full down-up cycle (100 @ 50 Hz ≈ 2 s per rep)
 * @param amplitude peak vertical acceleration swing, in m/s^2
 * @param noise magnitude of random noise added to every axis
 * @param seed makes the trace reproducible
 */
fun syntheticSquatTrace(
    reps: Int = 10,
    samplesPerRep: Int = 100,
    amplitude: Float = 6f,
    noise: Float = 0.3f,
    seed: Long = 42L
): List<MotionFrame> {
    val rng = Random(seed)
    val gravity = 9.8f
    val dtMs = 20L // 50 Hz -> 20 ms between samples
    val total = reps * samplesPerRep
    fun noiseVal(): Float = (rng.nextFloat() * 2f - 1f) * noise

    return (0 until total).map { i ->
        val phase = (i % samplesPerRep).toFloat() / samplesPerRep // 0..1 within one rep
        val cycle = sin(2.0 * PI * phase).toFloat()               // one full sine cycle per rep
        MotionFrame(
            tMillis = i * dtMs,
            ax = noiseVal(),
            ay = gravity - amplitude * cycle + noiseVal(),         // dips then rises around gravity
            az = noiseVal(),
            gx = 0.2f * cycle + noiseVal() * 0.1f,
            gy = noiseVal() * 0.1f,
            gz = noiseVal() * 0.1f
        )
    }
}