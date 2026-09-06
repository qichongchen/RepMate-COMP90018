# repmate-engine

A plain IntelliJ Kotlin module for prototyping the RepMate rep-detection engine
before it moves into the Android app. Everything here is **pure JVM Kotlin** with no
`android.*` imports, so the signal-processing code can be developed and tested off-device.

See `CLAUDE.md` for the full project context, data contracts, and scope rules.

## Layout

```
src/                     engine sources
  Models.kt              frozen data models (MotionFrame, RepEvent, RepScore, ...)
  RepDetector.kt         SquatRepDetector: magnitude + smoothing + state machine
  TraceLoader.kt         Sensor Logger CSV -> List<MotionFrame>
  TraceExpectation.kt    ground truth for one recording, parsed from a .expect file
  LabelledTrace.kt       a recording paired with its ground truth, plus window slicing
  TraceLibrary.kt        discovers every recording in traces/
  SyntheticTrace.kt      generated trace with a known rep count
  Main.kt                runs the detector over every recording and prints a report
test/                    replay tests, iterating whatever traces/ contains
traces/                  recordings, each with a companion .expect file
```

## Adding a recording

Drop two files into `traces/` and you are done — **no test code changes**. Both the demo
and the whole test suite iterate `TraceLibrary.loadAll()`, so a new recording is picked up
automatically:

- `my_trace.csv` — a Sensor Logger **Total Acceleration** export (gravity included; the
  detector depends on that offset being present).
- `my_trace.expect` — its ground truth, as `key = value` lines:

```
exercise        = SQUAT     # SQUAT | PUSHUP | JUMPING_JACK
reps            = 10        # repetitions actually performed, inside the window below
setStartMs      = 12000     # start of the real set, ms from the start of the recording
setEndMs        = 54000     # end of the real set
tolerance       = 0         # optional: reps the detector may be out by and still pass
fullTraceEvents = 12        # optional: events across the whole file, handling included
quietStartMs    = 54000     # optional: a stretch where the phone was still...
quietEndMs      = 62000     # ...in which nothing may be detected
notes           = ...       # optional, never asserted on
```

The set window exists because a recording starts before the phone is in your pocket and
stops after it is back out, so the raw file contains movement bursts that are not reps.
The window lets a test assert the honest number — "in the span where 10 squats happened,
the detector found 10" — while `fullTraceEvents` separately pins what the raw file yields.

Two deliberate strictnesses: a `.csv` with no companion `.expect` is an error rather than
a skip (an unasserted recording would otherwise vanish from the suite silently), and an
unknown key in a `.expect` file is rejected (a typo'd `fullTraceEvent` would otherwise
switch off an assertion while the suite stayed green).

## Running the tests

There is no Gradle build here, so the test library is wired up through IntelliJ's
project settings rather than a build file. **The jars themselves are not in git**
(`lib/` is ignored), so a fresh clone needs one setup step.

The project already records the coordinate it needs in
`.idea/libraries/jetbrains_kotlin_test_junit.xml`, so IntelliJ will usually offer to
download the missing jars on its own when you open the project. If it does, accept and
you are done.

If it does not, add the library by hand — either way works:

- **Quickest:** open `test/RepDetectorTest.kt`, put the caret on the red
  `import kotlin.test.Test`, press `Alt+Enter`, and accept the offer to add
  `kotlin-test-junit` to the classpath.
- **Explicitly:** `Ctrl+Alt+Shift+S` → **Libraries** → **+** → **From Maven** →
  `org.jetbrains.kotlin:kotlin-test-junit:2.4.10` → tick transitive dependencies → OK,
  then set its scope to **Test**. This pulls JUnit 4.13.x with it.

Match the version to the `kotlin-stdlib` in `.idea/libraries/KotlinJavaRuntime.xml`
(currently 2.4.10).

Then run them: click the green arrow next to `class RepDetectorTest`, or right-click
`test/` → **Run 'Tests in repmate-engine'**. All eight should pass.

**Keep the run configuration's working directory at the project root.** The tests find
`traces/` by relative path, so they fail to locate the recordings from anywhere else.

## Running the demo

Run `main` in `src/Main.kt` (same working-directory rule). For every recording in
`traces/` it prints the detected reps against ground truth, a per-event table of timing
and amplitude marking anything outside the set window, and the rest between events.
