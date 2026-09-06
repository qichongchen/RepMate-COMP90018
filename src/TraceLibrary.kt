package engine

import java.io.File

/**
 * Finds every recorded trace in `traces/` and pairs it with its ground truth.
 *
 * The point of this type is that **adding a recording requires no code changes**: drop
 * `my_trace.csv` and `my_trace.expect` into `traces/`, and both the demo and the whole
 * test suite pick it up. That matters because the detector's thresholds are currently
 * tuned to a single recording from a single person at a single pace, and the only way to
 * find out whether they generalise is to make adding traces cheap.
 *
 * Pure Kotlin (`java.io` only, no `android.*`), so it runs in plain JVM unit tests.
 */
object TraceLibrary {

    /** Extension of a motion recording. */
    private const val TRACE_EXTENSION = "csv"

    /** Extension of the companion ground-truth file. */
    private const val EXPECTATION_EXTENSION = "expect"

    /**
     * Directories searched for `traces/`, in order.
     *
     * The IDE runs from the project root, but a test runner or a terminal may start a
     * level away, so we check upwards too rather than depending on the working directory.
     */
    private val CANDIDATE_DIRECTORIES = listOf("traces", "../traces", "../../traces")

    /** The traces directory, or null if none of the candidates exist. */
    fun directory(): File? =
        CANDIDATE_DIRECTORIES.map(::File).firstOrNull { it.isDirectory }

    /**
     * Loads every trace in `traces/`, sorted by name so iteration order is deterministic.
     *
     * A CSV with no companion `.expect` file is an **error**, not a skip: a recording
     * nobody has written ground truth for would otherwise vanish from the suite silently,
     * and the whole point of the folder is that everything in it is asserted on.
     *
     * @throws IllegalStateException if `traces/` cannot be found.
     * @throws IllegalArgumentException if a recording has no expectation file, or either
     *   file is malformed.
     */
    fun loadAll(): List<LabelledTrace> {
        val directory = directory() ?: error(
            "Could not find a traces/ directory. Looked in: " +
                CANDIDATE_DIRECTORIES.joinToString { File(it).absolutePath }
        )

        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals(TRACE_EXTENSION, ignoreCase = true) }
            .sortedBy { it.name }
            .map { load(it) }
    }

    /** Loads one recording and its companion expectation. */
    private fun load(csv: File): LabelledTrace {
        val name = csv.nameWithoutExtension
        val expectationFile = File(csv.parentFile, "$name.$EXPECTATION_EXTENSION")
        require(expectationFile.isFile) {
            "Recording '${csv.name}' has no companion '$name.$EXPECTATION_EXTENSION'. " +
                "Every trace needs one so the suite knows what it should detect."
        }

        val frames = loadSensorLoggerCsv(csv.path)
        require(frames.isNotEmpty()) { "Recording '${csv.name}' contained no usable rows" }

        return LabelledTrace(
            name = name,
            frames = frames,
            expectation = loadTraceExpectation(expectationFile)
        )
    }
}
