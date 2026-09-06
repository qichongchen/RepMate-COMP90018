package engine

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
 * - `x`, `y`, `z` are **total** acceleration in m/s^2, i.e. gravity is still included.
 *   That matters: a phone at rest reads ~9.81, not ~0. The magnitude-based detector in
 *   [SquatRepDetector] depends on that gravity offset being present.
 *
 * Note the column order in the header: `z,y,x` — reversed from what you would expect.
 * That is why this parser resolves columns **by header name**, never by position; a
 * positional parser would silently swap the axes.
 *
 * ## Gyroscope
 * A Total Acceleration export has no gyroscope channel, so `gx`/`gy`/`gz` are set to
 * `0f`. This is the graceful-degradation rule: a missing sensor yields a sensible
 * default rather than a failure. Nothing in the squat pipeline reads the gyro today.
 *
 * Pure Kotlin (JVM `java.io` only, no `android.*`), so it runs in plain unit tests.
 */

/** Header names this parser understands, lower-cased for tolerant matching. */
private const val COL_SECONDS = "seconds_elapsed"
private const val COL_X = "x"
private const val COL_Y = "y"
private const val COL_Z = "z"

/**
 * Reads a Sensor Logger CSV from disk into frames.
 *
 * @param path path to the exported CSV file.
 * @return the frames in file order (Sensor Logger already writes them chronologically).
 * @throws IllegalArgumentException if the file is missing, empty, or lacks a required column.
 */
fun loadSensorLoggerCsv(path: String): List<MotionFrame> {
    val file = File(path)
    require(file.isFile) { "Trace file not found: ${file.absolutePath}" }
    return parseSensorLoggerCsv(file.readLines())
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
 * @throws IllegalArgumentException if there is no header or a required column is absent.
 */
fun parseSensorLoggerCsv(lines: List<String>): List<MotionFrame> {
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
                ax = x, ay = y, az = z,
                // No gyroscope channel in a Total Acceleration export — degrade to zeros.
                gx = 0f, gy = 0f, gz = 0f
            )
        }
        .toList()
}
