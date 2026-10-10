# Diagram 1 — System architecture

Layered view. Every box is annotated with the package (module boundary) it lives in.
Referenced from REPORT.md §5.1 (Implementation — Quality).

```mermaid
graph TD
  subgraph UI["UI layer &mdash; com.repmate.ui.* (Jetpack Compose)"]
    SCR["Screens<br/>Home, Live Workout, Push-up Workout,<br/>History, Session Detail, Motion Replay,<br/>Leaderboard, Friends, Ghost Duel, Profile"]
    VM["ViewModels<br/>one immutable UiState per screen,<br/>exposed as StateFlow"]
  end

  subgraph DOMAIN["Domain layer &mdash; pure Kotlin, no Android imports"]
    ENG["com.repmate.engine<br/>SquatRepDetector, JumpingJackRepDetector,<br/>PushupRepDetector, FormScorer,<br/>CalibrationProfile, SessionReplayer"]
    POSE["com.repmate.pose<br/>PoseGeometry, ArmLock, CountingGate"]
  end

  subgraph DATA["Data layer &mdash; com.repmate.data.*"]
    REPO["repo &mdash; interfaces<br/>SessionRepository, FriendRepository,<br/>LeaderboardRepository, CalibrationRepository"]
    SYNC["sync &mdash; SyncingSessionRepository,<br/>LeaderboardSync, LocalBestScoreSync,<br/>GuestHistoryMigrator"]
    LOCAL["local &mdash; Room<br/>SessionDao, CalibrationProfileDao"]
    CLOUD["cloud &mdash; Firestore data sources"]
    MEM["memory &mdash; JustFinishedSessionStore<br/>(replay frames, in-process only)"]
  end

  subgraph PLAT["Platform"]
    SENS["com.repmate.sensors<br/>DeviceSensorSource, PhoneStabilityGate"]
    HAL["Sensor HAL<br/>TYPE_ACCELEROMETER + TYPE_GYROSCOPE"]
    CAM["CameraX + ML Kit Pose"]
    FB["Firebase<br/>Auth &bull; Firestore &bull; security rules"]
    ROOM[("Room / SQLite<br/>on-device")]
    WM["WorkManager<br/>safety check-in"]
  end

  SCR --> VM
  VM --> ENG
  VM --> POSE
  VM --> REPO
  VM --> SENS
  REPO -.implemented by.-> SYNC
  SYNC --> LOCAL
  SYNC --> CLOUD
  VM --> MEM
  LOCAL --> ROOM
  CLOUD --> FB
  SENS --> HAL
  POSE --> CAM
  VM --> WM

  classDef pure fill:#ffffff,stroke:#000,stroke-width:2px,stroke-dasharray: 0;
  classDef plat fill:#eeeeee,stroke:#000,stroke-width:1px;
  class ENG,POSE pure;
  class HAL,CAM,FB,ROOM,WM plat;
```

**Boundary that matters:** `com.repmate.engine` and `com.repmate.pose` contain no Android
imports at all, which is why 426 unit tests run on the JVM with no device or emulator.
Hilt wires the arrows (`di/RepositoryModule`, `di/SensorModule`, `di/SafetyModule`).
