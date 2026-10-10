# Diagram 4 — Cloud data model and sync

Collections, who reads and writes each, and the offline&rarr;online reconciliation path.
Shapes and rules taken from `firestore.rules` and `com.repmate.data.cloud.*`.
Referenced from REPORT.md §5.3 (Implementation — Connectivity).

```mermaid
graph TB
  subgraph DEV["On device &mdash; source of truth"]
    ROOM[("Room<br/>workoutSessions + repScores<br/>calibrationProfiles")]
    DS[("DataStore<br/>preferences,<br/>pending guest migrations")]
  end

  subgraph FS["Cloud Firestore"]
    U["users/{uid}<br/><i>displayName, createdAt</i>"]
    N["usernames/{lowercased}<br/><i>uid, createdAt</i><br/><b>claim ticket &mdash; no update rule</b>"]
    W["users/{uid}/workoutSessions/{id}<br/><i>session + rep scores</i>"]
    FR["users/{uid}/friends/{friendId}"]
    RQ["users/{uid}/friendRequests/{senderId}<br/><i>fromUserId, displayName, createdAt</i>"]
    GS["users/{uid}/ghostScores/{exercise}<br/><i>averageScore, repCount, startedAt</i>"]
    LB["leaderboard/{uid}<br/><i>displayName, points, updatedAt</i>"]
  end

  SAVE["SyncingSessionRepository.save()"]
  LBS["LeaderboardSync<br/>recompute from local history"]
  LBest["LocalBestScoreSync<br/>publish best per exercise"]
  MIG["GuestHistoryMigrator<br/>+ PendingMigrationStore"]

  SAVE -->|"1. always, first"| ROOM
  SAVE -->|"2. best effort"| W
  SAVE -->|"3. application scope,<br/>never blocks the UI"| LBest
  SAVE --> LBS
  LBest --> GS
  LBS --> LB
  ROOM -.->|"recompute, idempotent"| LBS
  ROOM -.->|"recompute, idempotent"| LBest
  MIG --> ROOM
  MIG --> W
  DS -.->|"resumed on app start"| MIG

  LB -->|"read: any signed-in user"| GLOBAL["Leaderboard &mdash; Global tab"]
  GS -->|"get only, list denied"| FRIENDS["Leaderboard &mdash; Friends tab<br/>+ Ghost Duel"]
  U --> FRIENDS

  classDef cloud fill:#eeeeee,stroke:#000;
  class U,N,W,FR,RQ,GS,LB cloud;
```

## Who may read and write each collection

| Collection | Read | Write | Enforcement worth noting |
| --- | --- | --- | --- |
| `users/{uid}` | any signed-in (`get`); `list` **denied** | owner only | rename must not leave the old name claimed |
| `usernames/{name}` | any signed-in (`get`); `list` **denied** | create by owner; **no `update` rule at all** | uniqueness is a document-id collision, not a query |
| `workoutSessions` | owner | owner | — |
| `friends` | owner | owner, **or** the accepter creating the reverse side | reverse side allowed only if a matching request exists |
| `friendRequests` | recipient and sender | sender creates, recipient deletes; `update` denied | — |
| `ghostScores` | any signed-in (`get`); `list` **denied** | owner | so it cannot back a global board |
| `leaderboard/{uid}` | any signed-in | owner only | `points` is an int bounded 0&ndash;100000 |

## Offline &rarr; online reconciliation

Room is written **first and unconditionally**; a Firestore failure is logged and the workout
still completes. Catch-up is by **recompute-and-republish from local history**, not by a
retry queue — `LeaderboardSync` and `LocalBestScoreSync` recompute the best session per
exercise from Room and write it again. That is idempotent, so a workout finished offline
counts exactly once when connectivity returns, and running it twice changes nothing.

Ghost scores additionally use a **Firestore transaction** so a lower score can never
overwrite a higher one in a race between devices.
