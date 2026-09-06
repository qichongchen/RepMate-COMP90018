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
  SyntheticTrace.kt      generated trace with a known rep count
  Main.kt                runs the detector over the recorded trace and prints a report
test/                    replay tests
squat_10_pocket.csv      real recording: 10 squats, phone in pocket, ~99 Hz
```

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
`test/` → **Run 'Tests in repmate-engine'**. All five should pass.

**Keep the run configuration's working directory at the project root.** The tests load
`squat_10_pocket.csv` by relative path, so they fail to find it from anywhere else.

## Running the demo

Run `main` in `src/Main.kt` (same working-directory rule). It loads the recorded trace,
reports the detected reps with their timing and amplitude, and prints the rest between
consecutive reps.
