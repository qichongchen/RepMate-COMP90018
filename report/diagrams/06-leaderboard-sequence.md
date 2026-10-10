# Diagram 6 — End-to-end sequence: a finished workout reaching other devices

The leaderboard write path, from the last rep to another signed-in user's screen updating.
Referenced from REPORT.md §5.3 (Implementation — Connectivity).

```mermaid
sequenceDiagram
  autonumber
  participant U as User
  participant VM as LiveWorkoutViewModel
  participant SR as SyncingSessionRepository
  participant RM as Room (local)
  participant LS as LeaderboardSync
  participant LP as LeaderboardPoints
  participant FW as FirestoreLeaderboardWriter
  participant FS as Firestore + rules
  participant OD as Another device

  U->>VM: taps Finish
  VM->>VM: FormScorer scores each RepEvent
  VM->>SR: save(session)
  SR->>RM: save(session)
  Note over SR,RM: local write is first and unconditional --<br/>a cloud failure must never lose a workout
  RM-->>SR: stored
  SR->>FS: upload session document
  alt offline or rules reject
    FS-->>SR: failure
    SR->>SR: log and continue -- save() still succeeds
  else accepted
    FS-->>SR: ok
  end
  SR-)LS: recompute (application scope, not awaited)
  Note over SR,LS: launched off the UI path so the finish screen<br/>never waits on Firestore round trips
  LS->>RM: best session per exercise
  RM-->>LS: rows
  LS->>LP: points = sum of (avg rep score x rep count)
  LP-->>LS: points
  LS->>LS: skip if guest or no claimed display name
  LS->>FW: write(displayName, points)
  FW->>FS: set leaderboard/{uid}
  FS->>FS: rules: uid matches, exactly<br/>3 fields, 0 <= points <= 100000,<br/>updatedAt == request.time
  alt rules reject
    FS-->>FW: PERMISSION_DENIED
    FW-->>LS: failure returned, logged, not thrown
  else accepted
    FS-->>FW: committed
    FS-)OD: snapshot listener fires
    OD->>OD: Global tab re-renders with the new row
  end
```

**Why the recompute is not a retry queue.** `LeaderboardSync` recomputes the user's total
from whatever is in Room at the time, so it is idempotent and self-healing: a workout
finished offline is included the next time the sync runs, and running it twice writes the
same number. The same method is called from two places — after a workout saves, and after
History restores sessions from the cloud.
