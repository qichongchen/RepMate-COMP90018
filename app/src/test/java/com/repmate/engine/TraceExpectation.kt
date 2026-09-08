package com.repmate.engine

import java.io.File

/**
 * Ground truth for one recorded trace: what the human actually did, so tests can assert
 * against reality rather than against whatever the detector happened to produce.
 *
 * Every CSV in `traces/` has a companion `.expect` file with the same base name
 * (`squat_10_pocket.csv` -> `squat_10_pocket.expect`). Adding a recording therefore means
 * dropping in two files; no test code changes.
 *
 * ## Why a set window rather than just a rep count
 * A recording starts before the phone is in the pocket and stops after it is back out, so
 * the raw file contains movement bursts that are not reps. Those are real signal — the
 * detector is right to see them — but they are not what we are grading. [setStartMs] and
 * [setEndMs] mark the span of the actual set, so a test can slice to it and assert the
 * honest number: "in the window where 10 squats happened, the detector found 10."
 *
 * [fullTraceEvents] keeps the other half of the story: how many events the whole
 * recording yields, handling artifacts included. Pinning both means a change in
 * thresholds cannot quietly shift either number.
 *
 * @property exercise which movement was performed.
 * @property reps ground-truth repetitions performed inside the set window.
 * @property setStartMs start of the set, in milliseconds from the beginning of the recording.
 * @property setEndMs end of the set, same clock.
 * @property tolerance how many reps the detector may be out by and still pass. Defaults to
 *   0 (exact). A noisy or fast recording can declare 1 without touching test code.
 * @property fullTraceEvents events expected over the *entire* recording, handling bursts
 *   included, or null to not assert it.
 * @property quietStartMs start of a period where the phone was still, or null if the
 *   recording has no such stretch.
 * @property quietEndMs end of that still period.
 * @property units the scale the companion CSV's acceleration columns are written in.
 *   Defaults to m/s^2, which is what Sensor Logger's Android export uses; an iOS recording
 *   must declare `units = g`.
 * @property notes free text for the human; never asserted on.
 */
data class TraceExpectation(
    val exercise: ExerciseType,
    val reps: Int,
    val setStartMs: Long,
    val setEndMs: Long,
    val tolerance: Int = 0,
    val fullTraceEvents: Int? = null,
    val quietStartMs: Long? = null,
    val quietEndMs: Long? = null,
    val units: AccelerationUnit = AccelerationUnit.METRES_PER_SECOND_SQUARED,
    val notes: String? = null
) {
    init {
        require(reps >= 0) { "reps cannot be negative" }
        require(tolerance >= 0) { "tolerance cannot be negative" }
        require(setEndMs > setStartMs) { "setEndMs must come after setStartMs" }
        require((quietStartMs == null) == (quietEndMs == null)) {
            "quietStartMs and quietEndMs must be given together or not at all"
        }
        if (quietStartMs != null && quietEndMs != null) {
            require(quietEndMs > quietStartMs) { "quietEndMs must come after quietStartMs" }
        }
    }

    /** True when [detected] is within [tolerance] of the ground-truth [reps]. */
    fun acceptsRepCount(detected: Int): Boolean =
        detected in (reps - tolerance)..(reps + tolerance)

    /** Human-readable form of the accepted range, for assertion messages. */
    fun acceptedRangeDescription(): String =
        if (tolerance == 0) "$reps" else "${reps - tolerance}..${reps + tolerance}"
}

/** Keys a `.expect` file may contain. Anything else is a typo and is rejected. */
private val KNOWN_KEYS = setOf(
    "exercise", "reps", "setStartMs", "setEndMs", "tolerance",
    "fullTraceEvents", "quietStartMs", "quietEndMs", "units", "notes"
)

/**
 * Accepted spellings of the `units` key.
 *
 * ## Why the unit lives in the ground-truth file
 * [TraceLibrary] discovers recordings by scanning a directory, so there is no per-trace call
 * site in code where a unit could be passed — the `.expect` file *is* the call site. An iOS
 * and an Android export have byte-identical headers, so the alternative would be guessing
 * from the data (a still phone reads ~1 or ~9.81), and a guess that goes wrong yields a
 * silently empty rep count rather than an error.
 *
 * This does stretch what a `.expect` file is: it is now "the manifest for this recording"
 * rather than purely "what the human did". That is the trade accepted here, and it is why
 * the key is validated against this map rather than parsed loosely — an unrecognised spelling
 * is rejected outright rather than falling back to a default that would be wrong.
 */
private val UNIT_SPELLINGS = mapOf(
    "ms2" to AccelerationUnit.METRES_PER_SECOND_SQUARED,
    "m/s^2" to AccelerationUnit.METRES_PER_SECOND_SQUARED,
    "g" to AccelerationUnit.G,
)

/** Reads and parses a `.expect` file from disk. */
fun loadTraceExpectation(file: File): TraceExpectation {
    require(file.isFile) { "Expectation file not found: ${file.absolutePath}" }
    return parseTraceExpectation(file.readLines(), file.name)
}

/**
 * Parses `key = value` lines into a [TraceExpectation].
 *
 * Blank lines and `#` comments are ignored. An **unknown key is an error**, not something
 * to skip: a typo'd `fullTraceEvent` would otherwise silently switch off an assertion and
 * the suite would still go green, which is the worst possible failure mode for a test
 * fixture.
 *
 * Kept separate from [loadTraceExpectation] so it can be tested on literal lines.
 *
 * @param lines the file's contents.
 * @param source file name used in error messages.
 * @throws IllegalArgumentException on a malformed line, an unknown key, a bad value, or a
 *   missing required key.
 */
fun parseTraceExpectation(lines: List<String>, source: String = "<literal>"): TraceExpectation {
    val values = mutableMapOf<String, String>()

    lines.forEachIndexed { i, raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@forEachIndexed

        val separator = line.indexOf('=')
        require(separator > 0) { "$source line ${i + 1}: expected 'key = value', got '$raw'" }

        val key = line.take(separator).trim()
        require(key in KNOWN_KEYS) { "$source line ${i + 1}: unknown key '$key'; known keys are $KNOWN_KEYS" }
        require(values.put(key, line.substring(separator + 1).trim()) == null) {
            "$source line ${i + 1}: duplicate key '$key'"
        }
    }

    fun required(key: String): String =
        values[key] ?: throw IllegalArgumentException("$source is missing required key '$key'")

    fun int(key: String, raw: String): Int =
        raw.toIntOrNull() ?: throw IllegalArgumentException("$source: '$key' must be a whole number, got '$raw'")

    fun long(key: String, raw: String): Long =
        raw.toLongOrNull() ?: throw IllegalArgumentException("$source: '$key' must be a whole number, got '$raw'")

    val exerciseName = required("exercise").uppercase()
    val exercise = ExerciseType.entries.firstOrNull { it.name == exerciseName }
        ?: throw IllegalArgumentException(
            "$source: unknown exercise '$exerciseName'; expected one of ${ExerciseType.entries}"
        )

    return TraceExpectation(
        exercise = exercise,
        reps = int("reps", required("reps")),
        setStartMs = long("setStartMs", required("setStartMs")),
        setEndMs = long("setEndMs", required("setEndMs")),
        tolerance = values["tolerance"]?.let { int("tolerance", it) } ?: 0,
        fullTraceEvents = values["fullTraceEvents"]?.let { int("fullTraceEvents", it) },
        quietStartMs = values["quietStartMs"]?.let { long("quietStartMs", it) },
        quietEndMs = values["quietEndMs"]?.let { long("quietEndMs", it) },
        units = values["units"]?.let { raw ->
            UNIT_SPELLINGS[raw.lowercase()] ?: throw IllegalArgumentException(
                "$source: unknown units '$raw'; expected one of ${UNIT_SPELLINGS.keys}"
            )
        } ?: AccelerationUnit.METRES_PER_SECOND_SQUARED,
        notes = values["notes"]
    )
}
