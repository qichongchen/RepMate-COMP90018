# Viva preparation — Mohit Nanda, Sensors & Engine + Safety check-in

Prepared from `main` at `59e7430`. Ownership figures are **line-level `git blame`** with
`.mailmap` applied and `-w -M -C` (whitespace-insensitive, follows moves and copies), not commit
counts. Commit counts flatter whoever commits in small pieces; blame says who the lines belong to
now.

No model answers here on purpose. Each question gives you the evidence location and one line on
what a complete answer must contain.

---

## 1. Ownership

### 1.1 Mine — be able to explain any line

100% unless noted.

| File | Lines | Yours |
| --- | --- | --- |
| `engine/RepDetector.kt` | 600 | 100% |
| `engine/CalibrationProfile.kt` | 486 | 100% |
| `engine/JumpingJackRepDetector.kt` | 301 | 97% (Henrico 10) |
| `engine/BurstDetector.kt` | 108 | 100% |
| `engine/TraceLoader.kt` | 156 | 100% |
| `engine/Models.kt` | 83 | 98% (Claire 2) |
| `engine/SyntheticTrace.kt` | 48 | 100% — **dead code, see Traps** |
| `sensors/SensorSource.kt` | 263 | 100% |
| `sensors/PhoneStabilityGate.kt` | 163 | 100% |
| `pose/PoseGeometry.kt` | 131 | 100% |
| `pose/ArmLock.kt` | 108 | 100% |
| `pose/CountingGate.kt` | 67 | 100% |
| `safety/` — 12 files | ~650 | 93–100% (Henrico 1–4 lines each) |
| `ui/workout/pushup/PushupPoseAnalyzer.kt` | 74 | 100% |
| `ui/workout/pushup/PushupAngleTrace.kt` | 99 | 100% |
| `ui/workout/pushup/Throttle.kt` | 20 | 100% |
| `ui/workout/pushup/PushupFrameProcessor.kt` | 139 | 96% (Claire 5) |
| `ui/workout/pushup/PushupWorkoutScreen.kt` | 677 | **83%** (Henrico 113) |
| `data/memory/JustFinishedSessionStore.kt` | 106 | 100% |
| `data/repo/LeaderboardPoints.kt` | 63 | 100% |
| `data/sync/LeaderboardSync.kt` | 72 | 89% (Lisa 4, Henrico 4) |
| `example/repmate/SensorProbeActivity.kt` | 274 | 95% (Henrico 13) |
| Engine/pose/sensors/safety tests | ~2,400 | 91–100% |

### 1.2 Boundary — know the contract, not the implementation

| File | Yours | Theirs | Why it is a seam |
| --- | --- | --- | --- |
| `engine/FormScorer.kt` | **0%** | Claire 209 | Lives in *your* package and consumes your `RepEvent`. You own the input type, she owns the rule. |
| `engine/SessionReplayer.kt` | 34% | Claire 100 | Re-runs *your* detector. You own determinism, she owns the replay API. |
| `engine/PushupRepDetector.kt` | **75%** | Henrico 26, Claire 14 | Henrico *created* the file; you rewrote most of it. See Traps. |
| `ui/workout/LiveWorkoutViewModel.kt` | **28%** | Henrico 246 | The only production caller of `SensorSource.frames`. The main-thread limitation lands on your engine but lives in his file. |
| `ui/calibration/CalibrationViewModel.kt` | 57% | Henrico 97 | Produces the `CalibrationProfile` your detector consumes. |
| `ui/calibration/CalibrationCapture.kt` | 92% | Henrico 8 | Yours, but driven by his ViewModel. |
| `ui/workout/pushup/PushupWorkoutViewModel.kt` | 66% | Henrico 91, Claire 70 | Drives your pose gating. |
| `data/repo/SessionRepository.kt` | 79% | Lisa 21 | The interface where your engine output leaves your code. |
| `data/sync/SyncingSessionRepository.kt` | **12%** | Lisa 105, Henrico 42 | Lisa's implementation of that interface. |
| `di/SensorModule.kt` | **0%** | Henrico 34 | Constructs your `DeviceSensorSource`. Know what it binds. |

### 1.3 Not mine — safe to defer

`data/local/*` (Lisa — Room), `data/cloud/*` (Lisa, Henrico), `firestore.rules` (7% yours —
Henrico 96, Lisa 87, Jasper 41), `ui/audio/JumpingJackMetronome.kt` (Henrico 144, **0% yours**),
all auth/display-name/navigation/theme screens (Henrico), `LeaderboardScreen`/`ViewModel` (Jasper),
`FirestoreGhostScoreDataSource` (Lisa).

### 1.4 Ownership mismatches the examiner may notice

1. **`FormScorer.kt` is 0% yours but sits in `com.repmate.engine`.** If you say "I own the
   engine", the obvious next question is the 4 / 3 / 1.5 weights — which are Claire's. Say so.
2. **`PushupRepDetector.kt`: Henrico created it, you own 75% of its current lines.** The report
   §4 credits Henrico with creating it. Both are true. Don't let a "who wrote this?" question
   catch you flat-footed either way.
3. **`JumpingJackMetronome.kt` is 0% yours** but reads `JumpingJackRepDetector.TARGET_BPM`, which
   is. You own the constant, not the audio.
4. **`PushupWorkoutScreen.kt` is 83% yours** — a 677-line Compose screen. That is a lot of UI for
   someone describing themselves as "Sensors & Engine". Be ready to own it or to explain why it
   grew that way.
5. **`SessionReplayer.kt` is 34% yours**, but the determinism argument in §5.5 and Appendix D is
   about *your* detector. You defend the property, Claire defends the class.

---

## 2. Seam map

### 2.1 `SensorSource` → `LiveWorkoutViewModel`

- **Crosses:** `Flow<MotionFrame>`, source → consumer. `MotionFrame` is yours
  (`engine/Models.kt`), seven fields, boot-clock ms.
- **Owner:** you on the producing side; Henrico owns `LiveWorkoutViewModel` (72% his).
- **Lifecycle:** cold `callbackFlow`. Collection registers the listeners; `awaitClose { stop() }`
  unregisters them — `sensors/SensorSource.kt:206–245`.
- **On cancellation:** hardware is released. A listener left registered keeps the sensor powered
  for the life of the process, which is exactly what `awaitClose` prevents.
- **On failure:** no accelerometer → the flow completes empty (`SensorSource.kt:214`), it does not
  throw. No gyroscope → gyro channels stay `0f` and detection continues.
- **Known defect at this seam:** the collection runs in `viewModelScope`
  (`Dispatchers.Main.immediate`), and `callbackFlow`'s producer inherits the collector's context,
  so your tick loop and every `detector.process()` run on the main thread. No `flowOn`, no
  `buffer`, no `conflate`. Report §5.4 states this. **The file is Henrico's; the consequence is
  yours.**

### 2.2 Detectors → `FormScorer` → UI state

- **Crosses:** `RepEvent` (yours) → `RepScore` (Claire's), one direction.
- `LiveWorkoutViewModel.kt:92` constructs `FormScorer()`; `:185–196` picks your detector and closes
  over `process`/`phase`.
- **Contract you must defend:** what a `RepEvent` guarantees — `startMs`, `endMs`, `amplitude`
  measured on the *smoothed* signal, monotonic, emitted only after every guard passes.
- **Contract you may defer:** how depth/tempo/consistency become a number.
- **Failure:** `FormScorer` accepts a `null` profile and degrades (no depth, no consistency). Your
  detector does not know or care.

### 2.3 `CalibrationProfile` — producer and consumers

- **Produced by:** `ui/calibration/CalibrationCapture.kt` (92% yours) driven by
  `CalibrationViewModel` (57% yours, 43% Henrico); persisted by Lisa's
  `RoomCalibrationRepository`.
- **Consumed by:** `SquatRepDetector(profile)` secondary constructor
  (`RepDetector.kt:247–252`) and `FormScorer`.
- **Only four of seven thresholds are calibrated.** `highThreshold`, `lowThreshold` and
  `smoothingWindowMs` are deliberately *not* — `RepDetector.kt` documents why.
- **Failure:** `null` profile → tuned defaults. Never a crash, never a placeholder amplitude.

### 2.4 `SessionReplayer` → `JustFinishedSessionStore`

- **Crosses:** `WorkoutSession` *with* `frames`, in memory only.
- **Why frames are not persisted:** `RoomSessionRepository` saves `frames = null`
  (`data/local/RoomSessionRepository.kt:53` and `:151`). A frames column would mean a schema
  migration and megabytes per workout for a screen opened once. The rationale is in
  `JustFinishedSessionStore.kt:8–35`.
- **Scope:** one slot, cleared when the next workout starts; IMU exercises only (`canReplay`), so
  a push-up set keeps the honest no-replay notice.
- **Failure:** process death → the session is still in Room, it just replays without a curve.

### 2.5 Safety check-in → WorkManager → notification and SMS

- **Entry:** `CheckInScheduler.scheduleAfterWorkout()` (`safety/CheckInScheduler.kt:20`), called
  by a workout ViewModel once a session is saved.
- **Chain:** `CheckInNotifyWorker` → (unacknowledged) → `CheckInEscalateWorker:28` → `doWork()`
  re-reads the ack flag and returns `Result.success()` without sending if it is set.
- **Ack path:** notification action → `CheckInAckReceiver` → `CheckInScheduler.acknowledge():36`.
- **Boundary:** everything WorkManager-specific is behind `CheckInWorkGateway` (yours), which
  `CheckInSchedulerTest` fakes. Hilt wiring is `di/SafetyModule.kt` + `SafetyBindingsModule.kt`
  (yours).
- **Failure:** `SafetyAlertSender` cannot detect "no SIM" / "radio off" / carrier rejection —
  `safety/SafetyAlertSender.kt:52` says so. Location may be `null`; the SMS then goes without a
  map link.

### 2.6 Your engine output → sync layer → Firestore

- **Your code ends** at `SessionRepository.save(session)` — the interface
  (`data/repo/SessionRepository.kt`, 79% yours).
- **Lisa's code begins** at `SyncingSessionRepository.save()` (12% yours): Room first, Firestore
  best-effort, then `republishLocalBestScores()` in the application scope.
- **Your `LeaderboardPoints.of()`** (`data/repo/LeaderboardPoints.kt:39`, 100% yours) is what both
  leaderboard tabs and Ghost Duel reduce to. `LeaderboardSync` (89% yours) calls it.
- **Failure:** a Firestore failure is logged and swallowed; the workout still finishes. Your
  engine never learns about it.

---

## 3. Tier 1 — what does this do (15)

| # | Question | Evidence | A complete answer must contain |
| --- | --- | --- | --- |
| 1.1 | Why `TYPE_ACCELEROMETER` and not `TYPE_LINEAR_ACCELERATION`? | `SensorSource.kt:70–85` | Magnitude is rotation-invariant; gravity gives a *fixed baseline* so thresholds are absolute levels; traces are total acceleration too, so live and replay stay interchangeable. |
| 1.2 | Why is the filter window a duration, not a sample count? | `RepDetector.kt:34–64` | Cutoff frequency is set by length *in time*; 25 samples was 250 ms at 99 Hz but 430 ms at 58 Hz; every threshold is measured on the filter's output, so the sample count re-tuned all of them. |
| 1.3 | How is the sample count derived at runtime? | `RepDetector.kt:46–57`, `:287–300` | Eviction on timestamps themselves, not an estimated rate — no warm-up, absorbs mid-set drift. |
| 1.4 | Why are only two of five `RepPhase` values reachable? | `RepDetector.kt:66–82` | Magnitude is direction-blind; reporting `BOTTOM`/`ASCENDING` would be a fabrication. |
| 1.5 | Why is a frame suppressed unless the accelerometer timestamp advanced? | `SensorSource.kt:230–231` | `SENSOR_DELAY_GAME` is a hint; re-emitting one reading under a new timestamp invents motion and flattens the amplitude the detector measures. |
| 1.6 | What is the `HandlerThread` for? | `SensorSource.kt:160–175` | ~100 callbacks/s across two sensors kept off the main looper; the `Handler` overload of `registerListener`. |
| 1.7 | Why does the tick loop keep a running deadline rather than `delay(20)`? | `SensorSource.kt:242–247` | `delay` in a loop accumulates drift (20 ms *plus* work); resync-on-fall-behind because a burst of frames is a worse lie than a missing one. |
| 1.8 | Why is `Reading` one immutable object rather than four `@Volatile` fields? | `SensorSource.kt:108–121` | Four fields can be read half-updated, producing a magnitude no sensor reported. |
| 1.9 | Walk through the squat state machine and each guard. | `RepDetector.kt:66–82`, `:83–138` | 10.15 / 9.7 hysteresis, and each of `minRepDurationMs`, `maxRepDurationMs`, `cooldownMs`, `minAmplitude` with the false positive it rejects. |
| 1.10 | Why is `maxRepDurationMs` checked mid-flight rather than at close? | `RepDetector.kt:106–127` | The 36.9 s stuck window; judging at close fixes the miscount and leaves the detector deaf during it. |
| 1.11 | Why does the jumping-jack detector pair two bursts per rep? | `JumpingJackRepDetector.kt` class KDoc | Jump-out and landing are two impacts; counting bursts would double-count. |
| 1.12 | What does `ArmLock` do and why lock at all? | `pose/ArmLock.kt:6–30` | 70 switches in 60 s when re-picking per frame; median of first *n* framed poses; returns `null` rather than falling back. |
| 1.13 | What does `PhoneStabilityGate` measure, and why gyro RMS plus accel *standard deviation*? | `sensors/PhoneStabilityGate.kt:10–25` | Rotation moves the camera's view; std-dev ignores fixed bias and orientation, so only *change* counts. Release ratio < 1 is the same Schmitt reasoning. |
| 1.14 | How does `TraceLibrary` find traces, and why is a missing `.expect` an error? | `app/src/test/java/com/repmate/engine/TraceLibrary.kt:50–95` | Classpath resource dir, `file:` URL assumption; a recording nobody wrote ground truth for would vanish silently. |
| 1.15 | Trace the safety check-in from end-of-workout to SMS. | §2.5 above | Both stages WorkManager; ack flag re-read *inside* `doWork()`, not at schedule time. |

---

## 4. Tier 2 — why this way (12)

Each has a real alternative you rejected.

| # | Question | Alternative rejected | Evidence |
| --- | --- | --- | --- |
| 2.1 | Why latest-sample pairing instead of aligning accelerometer and gyroscope events? | Event alignment / interpolation | `SensorSource.kt:48–66`. Must quote the staleness bound (< 20 ms at `SENSOR_DELAY_GAME`) and why it is irrelevant at a ~1 s rep timescale. |
| 2.2 | Why magnitude instead of a single axis, or a gravity-projected axis? | `ay` alone; low-pass gravity vector | `RepDetector.kt:9–26`. Must name the cost: direction is discarded. |
| 2.3 | Why a Schmitt trigger rather than one threshold plus debounce? | Single threshold + timer | `RepDetector.kt:66–82`. Hysteresis gap vs. time-based suppression. |
| 2.4 | Why did `cooldownMs` go from 2000 ms to 500 ms rather than being retuned? | A single "better" cooldown value | `RepDetector.kt:176–193`. The two jobs it was conflating; no value satisfies both the fast set and the wobble. |
| 2.5 | Why is `minAmplitude` 0.94 and not the midpoint of something rounder? | 0.75 (the previous value) | `RepDetector.kt:128–138`. Band 0.87–1.02 at a 250 ms filter; what the filter change moved. |
| 2.6 | Why are `highThreshold`/`lowThreshold` *not* calibrated per user? | Calibrating all seven | `RepDetector.kt:232–253`. They are positions relative to gravity, identical for every person. |
| 2.7 | Why is `smoothingWindowMs` not calibrated either? | Per-user filter length | Same block. Property of the signal, already rate-invariant, and changing it moves every amplitude the profile was just measured in. |
| 2.8 | Why a median filter on elbow angle rather than a mean? | Moving mean | `PushupRepDetector.kt` constants + KDoc. One mislabelled landmark at 40° drags a mean but not a median. |
| 2.9 | Why does `ArmLock` return `null` when the locked arm is missing, instead of using the other? | Fall back to the visible arm | `pose/ArmLock.kt:16–23`. Falling back reintroduces exactly the switching the class exists to prevent. |
| 2.10 | Why is the stability gate *beside* the detectors rather than inside them? | A gate field in the detector | `PhoneStabilityGate.kt` KDoc. Keeps `com.repmate.engine` free of Android and of camera concerns. |
| 2.11 | Why does the escalate worker re-check the ack flag inside `doWork()`? | Cancel the work on ack | `CheckInEscalateWorker.kt:28–37`. Cancellation races; re-reading at send time is the only ordering that cannot send after an ack. |
| 2.12 | Why `callbackFlow` + `awaitClose` rather than a start/stop API the ViewModel calls? | Manual lifecycle | `SensorSource.kt:196–250`. Registration/unregistration must be paired; tying it to collection makes leaking impossible. |

---

## 5. Tier 3 — where it breaks (10)

Honest answers expected. Each needs a "what I would do about it".

| # | Question | The honest position | Evidence |
| --- | --- | --- | --- |
| 3.1 | How do you know `minAmplitude = 0.94` generalises? | **You don't.** Band is 0.15 m/s² wide, both edges from one recording. Since the filter normalisation it carries the wobble/rep decision *alone* — duration can no longer help. | `RepDetector.kt:139–175`, report §5.5 |
| 3.2 | Where did `StabilityConfig`'s thresholds come from? | Typical sensor-noise figures. The code says *"a first proposal, not measured values"*. Never measured against a phone propped beside a real push-up set. | `PhoneStabilityGate.kt:10–12` |
| 3.3 | `pauseSeconds` is always `0f`. Is that a bug? | No — an explicit sentinel. Magnitude is direction-blind, so no "reached the bottom" timestamp exists to measure a dwell from. | `FormScorer.kt:84`, `:161` |
| 3.4 | What thread does your detector run on? | **Main.** `viewModelScope` is `Dispatchers.Main.immediate` and `callbackFlow`'s producer inherits it. No `flowOn`/`buffer`/`conflate`. Smooth playback is *observed*, not *guaranteed*. | report §5.4; `LiveWorkoutViewModel.kt:100`, `:161–166` |
| 3.5 | What is the battery cost of a 50 Hz dual-sensor stream? | **Unmeasured.** Nothing lowers the rate, batches events or backs off with the screen off. Unregistration is the only power management. | report §5.2 |
| 3.6 | What is your end-to-end rep-to-pixel latency? | **No number exists** anywhere in the code, tests or docs. The responsiveness claims are structural. | report §5.4 |
| 3.7 | Two screens collect `frames` at once — what happens? | They share the one registration and the first to cancel stops the hardware for both. Needs `shareIn`. | `SensorSource.kt:206–208` |
| 3.8 | Your jumping-jack detector assumes a metronome. What if the user ignores it? | Outside what the model handles — it pairs two bursts at a fixed 52 BPM. Defaults come from two recordings of one person on one phone. | `JumpingJackRepDetector.kt:275–299` |
| 3.9 | `ArmLock` picks wrong. What then? | It stays wrong until reset. No re-validation. | `pose/ArmLock.kt:25–28` |
| 3.10 | The SMS does not send. How do you know? | **You don't.** `SmsManager` returns nothing useful for no-SIM / radio-off / carrier rejection. | `SafetyAlertSender.kt:52` |

---

## 6. Tier 4 — cross-boundary (10)

These are the ones the professor flagged. Each needs a path across files.

| # | Question | Path to trace |
| --- | --- | --- |
| 4.1 | Your detector emits a `RepEvent`. What reaches the leaderboard? | `RepEvent` → `FormScorer.score()` → `RepScore` → `WorkoutSession` → `SessionRepository.save()` → `SyncingSessionRepository` → `LeaderboardSync` → your `LeaderboardPoints.of()` → `FirestoreLeaderboardWriter` → `leaderboard/{uid}`. |
| 4.2 | Firestore is unreachable mid-set. What does your engine see? | Nothing. Room is written first; the upload failure is logged and swallowed in `SyncingSessionRepository.save()`. Say where your responsibility stops. |
| 4.3 | A user has never calibrated. What changes in your detector, and what changes in the score? | Detector: tuned defaults via the `null`-profile constructor. Score: depth and consistency not scored at all — Claire's decision, not yours. |
| 4.4 | Who decides a rep is worth 10/10, you or the scorer? | You decide a rep *happened*; Claire decides what it is worth. Name the 4 / 3 / 1.5 weights as hers. |
| 4.5 | Motion Replay shows a curve. Where did the frames come from, given Room stores `frames = null`? | `LiveWorkoutViewModel` accumulates → `JustFinishedSessionStore.remember()` → `MotionReplayViewModel` → `SessionReplayer` re-runs *your* detector. |
| 4.6 | Why does replaying a session reproduce exactly the same reps? | Causal filter, pure detectors, same profile. Name what breaks it. |
| 4.7 | The phone has no gyroscope. Which features degrade, and which silently? | Rep detection unaffected (accelerometer-driven). `PhoneStabilityGate` loses its most important channel — trace whether anything tells the user. |
| 4.8 | Your `CalibrationProfile` is persisted by Lisa's Room code. What is the contract? | Four thresholds out, four back. What happens on a schema migration — `DatabaseMigrations.kt` is Lisa's. |
| 4.9 | A guest finishes a workout, then signs in. What happens to the sessions your engine produced? | `GuestHistoryMigrator` (Henrico, 28% yours): upload → re-own → forget, from the local Room copy. Know the ordering and why. |
| 4.10 | Ghost Duel compares two best scores. Which part is yours? | `LeaderboardPoints`/`ExerciseBest` reduction is yours; `FirestoreGhostScoreDataSource` is Lisa's; the screens are Henrico's. |

---

## 7. [CANNOT DEFEND]

Things you cannot currently answer from the repository.

1. **[CANNOT DEFEND] "What is your per-frame processing cost?"** No measurement exists.
   *Fix before Monday:* add a JVM benchmark test that feeds one trace through `SquatRepDetector`
   and asserts a wall-clock bound, or at minimum time it once and record the number. One hour.
2. **[CANNOT DEFEND] "What does a 50 Hz sensor stream cost in battery?"** No drain measurement.
   *Fix:* either take one reading with Battery Historian, or be ready to say plainly that it was
   never measured and name what you would measure. The second is acceptable; guessing is not.
3. **[CANNOT DEFEND] "Does the stability gate actually hold during real push-ups?"** The defaults
   are self-declared unmeasured and were never checked against floor vibration.
   *Fix:* debug builds already show the live readings — prop a phone beside one real set and write
   the numbers down. Thirty minutes, and it converts a weakness into evidence.
4. **[CANNOT DEFEND] "Does `minAmplitude` work for a fourth person?"** One recording sets both
   band edges. *Fix:* record one more participant's 10 squats, drop the pair into
   `app/src/test/resources/traces/`, and let `TraceLibrary` pick it up. If it passes, say so; if
   it fails, that is a *better* viva answer than silence.
5. **[CANNOT DEFEND] "Has the push-up camera path been tested by anyone but you?"** The thresholds
   come from one person, one session, one camera placement. No fix available by Monday — know the
   sentence.

---

## 8. Traps

1. **`engine/SyntheticTrace.kt` is dead code.** 48 lines, 100% yours, **zero references anywhere**
   in `main`, `test` or `androidTest` — and it is in the `main` source set, so it ships in the
   APK. Expect "what calls this?". Either delete it before Monday or have the reason ready.
2. **`OBSERVED_SOFT_HARD_BOUNDARY = 12.0f` is declared and never used**
   (`JumpingJackRepDetector.kt:286`). The KDoc says "deliberately not used", which is a defensible
   answer — but only if you give it before the examiner finds it.
3. **`FormScorer.kt` is 0% yours and sits in your package.** The single most likely attribution
   trap. If you are asked about the 4 / 3 / 1.5 weights, they are Claire's and documented as "not
   a value agreed with the rest of the team".
4. **`PushupRepDetector.kt`: report §4 credits Henrico with creating it; blame says you own 75% of
   it now.** Both true. Decide how you phrase it before you are asked.
5. **`PushupWorkoutScreen.kt` is 677 lines and 83% yours.** Large Compose file under your name
   while you describe yourself as engine-side.
6. **`SensorProbeActivity.kt` (274 lines, 95% yours) is not in the main manifest.** It is declared
   in `app/src/debug/AndroidManifest.xml` only, deliberately, because it is exported. That is a
   *good* answer — make sure you give it rather than looking surprised.
7. **The main-thread defect is in Henrico's file but is your engine's problem.** Do not blame the
   file owner; own the consequence.
8. **README vs code, now reconciled but worth knowing:** the README says amplitude varies **7.1×**
   between participants; `FormScorer.kt:17` says "about a 9x range". They measure different things
   (softest-rep-per-participant vs. all 43 reps pooled). If the examiner spots the 9×, this is the
   distinction.
9. **`FormScorer.kt:17` says "4 participants"; the trace corpus has four squat recordings from
   three people** (you recorded twice). The code comment is loose. Unresolved — do not assert four
   people.
10. **`JumpingJackMetronome.kt` is 0% yours** but depends on your `TARGET_BPM`. If asked "how do
    you keep audio and detection in sync", the mechanism is yours, the audio is Henrico's.
11. **Appendix D of the report is new and detailed.** If you cite it, be able to defend anything in
    it — it is all your material, but it is freshly written.
