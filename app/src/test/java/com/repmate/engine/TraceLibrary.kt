package com.repmate.engine

import java.io.File

/**
 * Finds every recorded trace bundled as a resource and pairs it with its ground truth.
 *
 * The point of this type is that **adding a recording requires no code changes**: drop
 * `my_trace.csv` and `my_trace.expect` into `app/src/test/resources/traces/`, and the whole
 * test suite picks it up. That matters because the detector's thresholds are tuned from a
 * handful of recordings, and the only way to find out whether they generalise is to make
 * adding traces cheap.
 *
 * ## Why resources rather than a relative path
 * This started life in a standalone JVM project where `traces/` sat next to the sources and
 * could be opened with a relative path. That does not survive the move into an Android
 * module: a unit test's working directory is decided by Gradle, not by the repository
 * layout, so a relative path is fragile at best and wrong at worst. Loading from the
 * classpath instead means Gradle tells us where the files are, and the traces travel with
 * the test source set.
 *
 * ## The file-URL assumption
 * Enumerating a *directory* of resources is not something the classloader API offers
 * directly, so [directory] resolves the `traces` resource to a URL and requires it to be a
 * `file:` URL — which is exactly what Gradle produces for unit tests, since it copies test
 * resources into `build/resources/test/` as ordinary directories. That is the only context
 * these fixtures are used in.
 *
 * If the traces were ever packaged inside a jar or an APK, the URL would use a different
 * protocol and enumeration would need a manifest of trace names instead. [directory] fails
 * with an explicit message in that case rather than silently finding nothing — a suite that
 * quietly discovers zero traces would pass every assertion while testing nothing.
 *
 * Pure Kotlin (`java.io` only, no `android.*`), so it runs as a plain JVM unit test with no
 * emulator.
 */
object TraceLibrary {

    /** Resource directory holding the recordings, relative to the classpath root. */
    private const val RESOURCE_DIRECTORY = "traces"

    /** Extension of a motion recording. */
    private const val TRACE_EXTENSION = "csv"

    /** Extension of the companion ground-truth file. */
    private const val EXPECTATION_EXTENSION = "expect"

    /**
     * The directory holding the traces, or null if the resource is absent from the classpath.
     *
     * @throws IllegalStateException if the resource exists but is not a plain directory on
     *   disk — see the file-URL note in the class documentation.
     */
    fun directory(): File? {
        val url = javaClass.classLoader?.getResource(RESOURCE_DIRECTORY) ?: return null
        check(url.protocol == "file") {
            "Traces resolved to a '${url.protocol}:' URL ($url) rather than a plain " +
                "directory. Enumerating traces packaged inside an archive is not supported; " +
                "it would need a manifest listing the trace names."
        }
        return File(url.toURI())
    }

    /**
     * Loads every trace, sorted by name so iteration order is deterministic.
     *
     * A CSV with no companion `.expect` file is an **error**, not a skip: a recording nobody
     * has written ground truth for would otherwise vanish from the suite silently, and the
     * whole point of the folder is that everything in it is asserted on.
     *
     * @throws IllegalStateException if the traces resource cannot be found on the classpath.
     * @throws IllegalArgumentException if a recording has no expectation file, or either
     *   file is malformed.
     */
    fun loadAll(): List<LabelledTrace> {
        val directory = directory() ?: error(
            "Could not find the '$RESOURCE_DIRECTORY' resource on the classpath. Unit-test " +
                "fixtures belong in app/src/test/resources/$RESOURCE_DIRECTORY/."
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

        // The expectation is read first because it declares the CSV's units: an iOS export is in
        // g and an Android one in m/s^2, and the two are indistinguishable from their headers
        // alone. Reading the manifest before the data is what makes the choice explicit here.
        val expectation = loadTraceExpectation(expectationFile)

        val frames = loadSensorLoggerCsv(csv.path, expectation.units)
        require(frames.isNotEmpty()) { "Recording '${csv.name}' contained no usable rows" }

        return LabelledTrace(
            name = name,
            frames = frames,
            expectation = expectation
        )
    }
}
