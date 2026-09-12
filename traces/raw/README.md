# Raw sensor recordings

The original [Sensor Logger](https://www.tszheichoi.com/sensorlogger) exports the committed
traces were derived from. One zip per participant recording, named to match the trace it
produced.

These are **source data, not test fixtures**. Nothing builds or tests against them — the
suite reads the single-channel CSVs in `app/src/test/resources/traces/`. They are here
because they are irreplaceable: re-deriving anything they contain means getting four people
back into a lab, one of whom recorded on their own phone in their own time.

**Privacy: `Location.csv` removed, and the device id blanked.** This repository is public,
and Sensor Logger records GPS alongside every other sensor. The pocket and fast exports
carried 50 and 36 precise location fixes — i.e. where the recordings were made — so that one
channel was stripped from both zips before they were first committed.

`Metadata.csv` also carried a per-install `device id`: a UUID stable across every recording
made on that phone, including for the two participants who are not on the team. It is not a
location and not an advertising id, but it is a persistent identifier for someone else's
device in a public repository, so it is blanked in all four zips.

That is the **only** field altered. Every other entry in every zip is exactly the original
export, verified entry by entry after the edit: 12 of 13 entries identical for pocket and
fast, 6 of 7 for hit, 5 of 6 for lisa, with `Metadata.csv` the sole difference and `device
id` the sole changed field within it. The channel each committed trace was lifted from is
untouched. The unstripped exports are kept off-repo by the person who recorded them.

## What produced what

Each committed trace is one channel lifted out of one zip, unmodified apart from the line
endings git normalises. Verified byte-for-byte at the time of commit.

| Raw zip | Channel taken | Committed trace | Device |
|---|---|---|---|
| `squat_10_pocket_raw.zip` | `TotalAcceleration.csv` | `squat_10_pocket.csv` | Pixel, ~99 Hz |
| `squat_10_fast_raw.zip` | `TotalAcceleration.csv` | `squat_10_fast.csv` | Pixel, ~99 Hz |
| `squat_10_hit_raw.zip` | `TotalAcceleration.csv` | `squat_10_hit.csv` | Samsung SM-S936B, ~58 Hz |
| `squat_10_lisa_raw.zip` | `AccelerometerUncalibrated.csv` | `squat_10_lisa.csv` | iPhone 14 Pro, ~100 Hz |

Lisa's is the odd one out, and deliberately so. Sensor Logger's iOS export has no
`TotalAcceleration.csv` at all, and iOS's `Accelerometer.csv` has gravity already removed —
a still phone reads ~0, so every threshold in `SquatRepDetector`, all of which sit around
9.81, would have nothing to sit against. `AccelerometerUncalibrated.csv` is the closest
equivalent: it keeps gravity, but is written in **g** rather than m/s², which is why the
`.expect` format carries a `units` key and the loader multiplies by 9.80665 on the way in.

## Why these are kept rather than deleted

The engine reads one channel. The zips hold every sensor the phone recorded, and two open
problems in `SquatRepDetector` need channels that are **only** in here:

1. **Restoring the second independent guard.** At a 250 ms filter, Hit's wobble runs 743 ms —
   longer than the shortest genuine rep in the library (615 ms) — so `minRepDurationMs` can
   no longer separate it from a real rep and `minAmplitude` carries that decision alone, on a
   0.15-wide band from one participant.
2. **Detecting a user who moves weakly.** The Schmitt trigger is a fixed offset from gravity
   (10.15 / 9.7). Replaying the traces at reduced force shows the trigger failing before any
   calibrated guard does: a rep whose window never opens cannot be recovered downstream.

Both point the same way — recovering **direction**, so a descent and an ascent can be told
apart instead of collapsing into one undifferentiated movement burst. That needs a gravity
vector, from `Gravity.csv` or integrated from `Gyroscope.csv`. Neither is in the committed
traces.

## What is actually in each zip

| Channel | pocket | fast | hit | lisa |
|---|---|---|---|---|
| `TotalAcceleration.csv` | ✅ | ✅ | ✅ | — |
| `Accelerometer.csv` (gravity removed) | ✅ | ✅ | ✅ | ✅ |
| `AccelerometerUncalibrated.csv` | ✅ | ✅ | ✅ | ✅ |
| `Gyroscope.csv` | ✅ | ✅ | ✅ | ✅ |
| `GyroscopeUncalibrated.csv` | ✅ | ✅ | ✅ | ✅ |
| `Gravity.csv` | ✅ | ✅ | — | — |
| `Orientation.csv` | ✅ | ✅ | — | — |
| `Magnetometer.csv` / uncalibrated | ✅ | ✅ | — | — |
| `Compass.csv`, `Barometer.csv` | ✅ | ✅ | — | — |
| `Location.csv` (GPS) | removed | removed | — | — |
| `Metadata.csv` (`device id` blanked) | ✅ | ✅ | ✅ | ✅ |
| `Annotation.csv` | ✅ | ✅ | ✅ | ✅ |

**Note the gap before planning direction-based work.** `Gravity.csv` is a
device-fused estimate and exists for only **two of the four** participants — both of them
Mohit, on the same Pixel. Hit and Lisa recorded fewer channels. `Gyroscope.csv` is present
for all four, so an approach that integrates the gyro, or low-pass-filters the accelerometer
into a gravity estimate, generalises across the whole library where one reading `Gravity.csv`
directly would work for half of it and would not be testable on the two phones that matter
most for device diversity.

## Adding another recording

Export from Sensor Logger, drop the zip here as `<trace_name>_raw.zip`, then extract the one
channel into `app/src/test/resources/traces/<trace_name>.csv` and write its companion
`.expect`. Prefer `TotalAcceleration.csv`; on iOS use `AccelerometerUncalibrated.csv` and
declare `units = g`. The channel you take should be recorded in the table above.

**Before committing, clean the zip.** Two steps, both easy to forget and both expensive to
undo — removing either later means rewriting shared history, and any clone made in between
keeps the original.

1. **Delete `Location.csv` if it is there.** This repository is public and Sensor Logger
   records GPS whenever location permission is on, so an export can carry the address it was
   recorded at.
2. **Blank the `device id` column in `Metadata.csv`.** It is a per-install UUID, stable
   across every recording from that phone, and for a participant outside the team it is
   their identifier, not yours to publish.

`Metadata.csv` also records the device name and recording timezone. Neither is a location
and both are left as they are, but check they say nothing you would not want public.
