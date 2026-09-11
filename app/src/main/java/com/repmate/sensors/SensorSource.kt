package com.repmate.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.repmate.engine.MotionFrame
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A source of [MotionFrame]s for the engine.
 *
 * The engine (detector, scorer, recorder) consumes [frames] and deliberately knows nothing about
 * where they came from: on a phone they come from [DeviceSensorSource], in unit tests they come
 * from a recorded trace replayed off disk. Same stream, same code path, so what we demo is what
 * we test.
 */
interface SensorSource {

    /**
     * A **cold** stream of paired accelerometer/gyroscope samples.
     *
     * Collecting it starts the hardware; cancelling the collection stops it again. Nothing is
     * sampled while nobody is collecting.
     */
    val frames: Flow<MotionFrame>

    /** Begin listening to the hardware. Idempotent - calling it twice registers nothing extra. */
    fun start()

    /** Stop listening and release the hardware. Idempotent, and safe to call before [start]. */
    fun stop()
}

/**
 * [SensorSource] backed by Android's [SensorManager].
 *
 * ## Sampling approach
 * The two sensors are *not* synchronised: the accelerometer and the gyroscope deliver their own
 * events, at their own times, with their own jitter, and there is no callback that hands you both
 * at once. So we do not try to align events. Instead:
 *
 * 1. Both sensors are registered at [SensorManager.SENSOR_DELAY_GAME] (~20 ms nominal, i.e. the
 *    ~50 Hz the engine is tuned for) and their callbacks only store the **latest** reading each.
 * 2. A separate coroutine ticks at a fixed [sampleRateHz] and pairs whatever the latest readings
 *    are into one [MotionFrame].
 *
 * This "latest value wins" pairing is the standard way to fuse unsynchronised sensors. Its cost is
 * that the gyroscope value in a frame can be up to one sensor period stale relative to the
 * accelerometer value - under 20 ms, far below the ~1 s timescale of a rep, so it cannot affect
 * rep counting or tempo.
 *
 * The tick loop keeps a running deadline rather than calling `delay(20)` in a loop, because the
 * latter accumulates drift (each iteration costs 20 ms *plus* whatever the work took). If we fall
 * behind - a GC pause, a slow collector - the deadline is resynced to now rather than sprinting to
 * catch up, since a burst of back-to-back frames would be a worse lie than a missing one.
 *
 * A frame is emitted only when the accelerometer timestamp has actually advanced. When the
 * hardware runs slower than our tick (SENSOR_DELAY_GAME is a hint, not a contract - the OS may
 * deliver slower), re-emitting the same reading under a new timestamp would invent motion data
 * that never existed and flatten the very signal the detector measures amplitude from.
 *
 * ## Why gravity must be included
 * We register [Sensor.TYPE_ACCELEROMETER], which reports **total** acceleration - the user's
 * movement *plus* the ~9.81 m/s^2 of gravity. That is deliberate and must not be "fixed" by
 * switching to `TYPE_LINEAR_ACCELERATION`.
 *
 * The detector works on the magnitude `sqrt(ax^2 + ay^2 + az^2)`, which is rotation-invariant: it
 * reads the same no matter what angle the phone sits at in a pocket. With gravity included, a
 * still phone parks that magnitude at a known constant ~9.81 and every real movement pushes it
 * away from that resting value, so the thresholds are absolute levels around a fixed baseline.
 * Strip gravity out and the resting value becomes ~0, every tuned threshold is off by 9.81, and
 * the recorded traces we tuned against stop matching live data. Those traces are total
 * acceleration too, which is what keeps live and replayed frames interchangeable.
 *
 * ## Timestamps
 * [MotionFrame.tMillis] comes from [SensorEvent.timestamp] (nanoseconds on the boot clock),
 * converted to milliseconds - never `System.currentTimeMillis()`. The wall clock can jump
 * backwards mid-workout (an NTP correction, the user editing the time) which would give a rep a
 * negative duration and break every tempo guard. The boot clock only moves forward, and the engine
 * only ever uses differences between frames, so its origin does not matter. If a session needs a
 * wall-clock date, record one `System.currentTimeMillis()` at session start and offset from it -
 * do not mix the two clocks inside the frame stream.
 *
 * ## Missing hardware
 * No gyroscope (common on budget devices): the gyro channels stay `0f` and everything else keeps
 * running, because rep detection is driven by the accelerometer alone. No accelerometer: there is
 * nothing meaningful to emit, so [frames] completes immediately rather than crashing or
 * fabricating readings - the caller sees an empty stream and can say so on screen.
 *
 * @param sensorManager injected rather than fetched from a `Context`, so this class holds no
 *   `Context` reference and is easy to fake in an instrumented test.
 * @param sampleRateHz how often frames are emitted. 50 Hz is what the engine's thresholds and the
 *   recorded traces assume; changing it invalidates that tuning.
 */
class DeviceSensorSource(
    private val sensorManager: SensorManager,
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
) : SensorSource {

    /**
     * One immutable sensor sample.
     *
     * It is a single object so the tick loop reads x/y/z and the timestamp as one consistent set.
     * Four separate `@Volatile` fields could be read half-updated (new x, old y) and yield a
     * magnitude that no sensor ever reported.
     */
    private data class Reading(
        val timestampNanos: Long,
        val x: Float,
        val y: Float,
        val z: Float,
    )

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** False on hardware with no accelerometer; [frames] is then an empty stream. */
    val hasAccelerometer: Boolean get() = accelerometer != null

    /** False on devices without a gyroscope; the gyro channels are then always zero. */
    val hasGyroscope: Boolean get() = gyroscope != null

    // Written on the sensor thread, read on the coroutine that emits frames.
    @Volatile private var latestAccel: Reading? = null

    @Volatile private var latestGyro: Reading = ZERO_READING

    private val lock = Any()
    private var registered = false
    private var sensorThread: HandlerThread? = null

    /**
     * One listener serves both sensors; [SensorEvent.sensor] says which one fired.
     *
     * It does no work beyond storing the reading, because it runs on the sensor delivery thread and
     * anything slow here delays the next sample.
     */
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val reading = Reading(event.timestamp, event.values[0], event.values[1], event.values[2])
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> latestAccel = reading
                Sensor.TYPE_GYROSCOPE -> latestGyro = reading
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Registers both sensors on a dedicated background thread.
     *
     * The `Handler` overload of `registerListener` is used so sensor callbacks arrive on that
     * thread instead of the main looper - at 50 Hz across two sensors that is ~100 callbacks a
     * second we keep off the UI thread.
     */
    override fun start() {
        synchronized(lock) {
            if (registered) return
            val accel = accelerometer ?: return
            val thread = HandlerThread(SENSOR_THREAD_NAME).apply { start() }
            val handler = Handler(thread.looper)
            sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME, handler)
            gyroscope?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
            sensorThread = thread
            registered = true
        }
    }

    /**
     * Unregisters both sensors and shuts the sensor thread down.
     *
     * A listener left registered keeps the sensor powered for as long as the process lives, so this
     * must run whenever a workout ends - hence [frames] calling it from `awaitClose`.
     */
    override fun stop() {
        synchronized(lock) {
            if (!registered) return
            sensorManager.unregisterListener(listener)
            sensorThread?.quitSafely()
            sensorThread = null
            latestAccel = null
            latestGyro = ZERO_READING
            registered = false
        }
    }

    /**
     * Cold stream of paired frames.
     *
     * `callbackFlow` is the right builder here because a `SensorEventListener` is a callback that
     * must be registered and unregistered in pairs: the flow registers when collection begins and
     * `awaitClose` unregisters when the collector cancels - including when a `ViewModel` scope dies
     * or the screen is closed - so the sensors cannot outlive the workout.
     *
     * NOTE: this is built for one collector at a time (one workout screen). Two simultaneous
     * collectors would share the single registration, and the first to cancel would stop the
     * hardware for both; wrap it with `shareIn` if that is ever needed.
     */
    override val frames: Flow<MotionFrame> = callbackFlow {
        start()

        if (accelerometer == null) {
            // NOTE: degrade gracefully - complete an empty stream rather than crash or invent data.
            close()
            return@callbackFlow
        }

        val periodNanos = NANOS_PER_SECOND / sampleRateHz

        // The pump runs as a child coroutine so that `awaitClose` below is reached immediately and
        // is still registered when the collector cancels; a `while` loop inline would never let
        // execution get there.
        launch {
            var nextTickNanos = System.nanoTime()
            var lastEmittedAccelNanos = Long.MIN_VALUE

            while (isActive) {
                val accel = latestAccel
                if (accel != null && accel.timestampNanos != lastEmittedAccelNanos) {
                    lastEmittedAccelNanos = accel.timestampNanos
                    val gyro = latestGyro
                    send(
                        MotionFrame(
                            tMillis = accel.timestampNanos / NANOS_PER_MILLI,
                            ax = accel.x, ay = accel.y, az = accel.z,
                            gx = gyro.x, gy = gyro.y, gz = gyro.z,
                        ),
                    )
                }

                nextTickNanos += periodNanos
                val now = System.nanoTime()
                if (nextTickNanos < now) nextTickNanos = now // fell behind: resync, don't catch up
                // coerceAtLeast(1) guarantees the loop suspends, so it can never spin the CPU.
                delay(((nextTickNanos - now) / NANOS_PER_MILLI).coerceAtLeast(1L))
            }
        }

        awaitClose { stop() }
    }

    private companion object {
        /** The rate the engine's thresholds and the recorded traces assume. */
        const val DEFAULT_SAMPLE_RATE_HZ = 50
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val SENSOR_THREAD_NAME = "RepMate-Sensors"

        /** Stand-in gyroscope reading, used before the first event and on devices without one. */
        val ZERO_READING = Reading(timestampNanos = 0L, x = 0f, y = 0f, z = 0f)
    }
}
