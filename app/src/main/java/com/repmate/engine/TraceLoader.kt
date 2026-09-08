package com.repmate.engine

import java.io.File

/**
 * Loads motion traces recorded by the **Sensor Logger** phone app (CSV export).
 *
 * ## The file format
 * Sensor Logger writes one CSV per sensor. A "Total Acceleration" export looks like:
 *
 * ```
 * time,seconds_elapsed,z,y,x
 * 1788594189329554400,0.161554443359375,7.8427315,5.7617211,-0.5300164
 * ```
 *
 * - `time` is a Unix timestamp in **nanoseconds** — we ignore it, because the engine
 *   only ever needs time *relative to the start of the recording*.
 * - `seconds_elapsed` is seconds since recording began; multiplying by 1000 gives the
 *   [MotionFrame.tMillis] the engine expects.
 * - `x`, `y`, `z` are acceleration on each axis, **including gravity**. That matters: a
 *   phone at rest reads ~9.81 m/s^2 (or ~1.0 g), not ~0. The magnitude-based detector in
 *   [SquatRepDetector] depends on that gravity offset being present.
 *
 * Note the column order in the header: `z,y,x` — reversed from what you would expect.
 * That is why this parser resolves columns **by header name**, never by position; a
 * positional parser would silently swap the axes.
 *
 * ## Android and iOS export different files, in different units
 * Sensor Logger names and scales its exports differently per platform, and the headers are
 * identical, so nothing in the file itself reveals which you have:
 *
 * | Platform | Use this file                   | Units | Gravity |
 * |----------|---------------------------------|-------|---------|
 * | Android  | `TotalAcceleration.csv`         | m/s^2 | included |
 * | iOS      | `AccelerometerUncalibrated.csv` | **g** | included |
 *
 * On iOS there is no `TotalAcceleration.csv` at all, and the `Accelerometer.csv` that
 * *is* there has gravity already removed, which makes it useless to us — a still phone
 * reads ~0 and every threshold in [SquatRepDetector] is calibrated around ~9.81.
 *
 * Because the two formats are indistinguishable from their contents, [AccelerationUnit] is
 * a **required** argument rather than a defaulted one. Loading g-units as m/s^2 would
 * quietly scale every reading down by 9.8, and the detector would simply report zero reps
 * on a perfectly good recording — a silent wrong answer, which is the failure this API
 * shape exists to make impossible.
 *
 * ## Gyroscope
 * These acceleration exports carry no gyroscope channel, so `gx`/`gy`/`gz` are set to
 * `0f`. This is the graceful-degradation rule: a missing sensor yields a sensible
 * default rather than a failure. Nothing in the squat pipeline reads the gyro today.
 *
 * Pure Kotlin (JVM `java.io` only, no `android.*`), so it runs in plain unit tests.
 */

/**
 * Standard gravity, in m/s^2 — the exact CODATA/SI constant, not a rounded 9.81.
 *
 * This is the number Sensor Logger's iOS export is implicitly divided by, so multiplying by
 * exactly this value is what recovers the original m/s^2 readings.
 */
const val STANDARD_GRAVITY = 9.80665f

/**
 * The unit a trace's acceleration columns are written in.
 *
 * @property toMetresPerSecondSquared factor converting one unit of this scale into m/s^2,
 *   which is what [MotionFrame] stores and what every threshold in [SquatRepDetector] assumes.
 */
enum class AccelerationUnit(val toMetresPerSecondSquared: Float) {

    /** Sensor Logger on **Android**: `TotalAcceleration.csv`, already in m/s^2. */
    METRES_PER_SECOND_SQUARED(1f),

    /** Sensor Logger on **iOS**: `AccelerometerUncalibrated.csv`, in multiples of gravity. */
    G(STANDARD_GRAVITY),
}

/** Header names this parser understands, lower-cased for tolerant matching. */
private const val COL_SECONDS = "seconds_elapsed"
private const val COL_X = "x"
private const val COL_Y = "y"
private const val COL_Z = "z"

/**
 * Reads a Sensor Logger CSV from disk into frames.
 *
 * @param path path to the exported CSV file.
 * @param unit which scale that file's acceleration columns are in — [AccelerationUnit.G] for an
 *   iOS `AccelerometerUncalibrated.csv`, [AccelerationUnit.METRES_PER_SECOND_SQUARED] for an
 *   Android `TotalAcceleration.csv`. Deliberately has no default; see the class docs.
 * @return the frames in file order (Sensor Logger already writes them chronologically).
 * @throws IllegalArgumentException if the file is missing, empty, or lacks a required column.
 */
fun loadSensorLoggerCsv(path: String, unit: AccelerationUnit): List<MotionFrame> {
    val file = File(path)
    require(file.isFile) { "Trace file not found: ${file.absolutePath}" }
    return parseSensorLoggerCsv(file.readLines(), unit)
}

/**
 * Parses already-read CSV lines into frames.
 *
 * Kept separate from [loadSensorLoggerCsv] so tests can feed a few literal lines in
 * without touching the filesystem.
 *
 * Malformed data rows (wrong column count, unparseable numbers) are skipped rather
 * than thrown on: a real recording can end mid-line if the app is killed, and losing
 * one sample of 6500 must not lose the whole session.
 *
 * @param lines every line of the CSV, header first.
 * @param unit which scale the acceleration columns are in. Every reading is multiplied by
 *   [AccelerationUnit.toMetresPerSecondSquared], so frames always leave here in m/s^2 no
 *   matter which platform recorded them — the engine downstream never learns the difference.
 * @throws IllegalArgumentException if there is no header or a required column is absent.
 */
fun parseSensorLoggerCsv(lines: List<String>, unit: AccelerationUnit): List<MotionFrame> {
    val header = lines.firstOrNull { it.isNotBlank() }
        ?: throw IllegalArgumentException("CSV is empty — no header row")

    val columns = header.split(',').map { it.trim().lowercase() }
    fun indexOfColumn(name: String): Int {
        val i = columns.indexOf(name)
        require(i >= 0) { "CSV header is missing the '$name' column; found $columns" }
        return i
    }

    val secondsAt = indexOfColumn(COL_SECONDS)
    val xAt = indexOfColumn(COL_X)
    val yAt = indexOfColumn(COL_Y)
    val zAt = indexOfColumn(COL_Z)
    val widthNeeded = maxOf(secondsAt, xAt, yAt, zAt) + 1
    val scale = unit.toMetresPerSecondSquared

    return lines
        .asSequence()
        .drop(lines.indexOf(header) + 1)
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val cells = line.split(',')
            if (cells.size < widthNeeded) return@mapNotNull null

            val seconds = cells[secondsAt].trim().toDoubleOrNull() ?: return@mapNotNull null
            val x = cells[xAt].trim().toFloatOrNull() ?: return@mapNotNull null
            val y = cells[yAt].trim().toFloatOrNull() ?: return@mapNotNull null
            val z = cells[zAt].trim().toFloatOrNull() ?: return@mapNotNull null

            MotionFrame(
                tMillis = (seconds * 1000.0).toLong(),
                // Scaled here, once, so nothing downstream has to remember the platform.
                ax = x * scale, ay = y * scale, az = z * scale,
                // No gyroscope channel in an acceleration export — degrade to zeros.
                gx = 0f, gy = 0f, gz = 0f
            )
        }
        .toList()
}
