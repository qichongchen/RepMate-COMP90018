# Diagram 5 — Screen flow

Every destination registered in `RepMateDestinations` / `NavGraph.kt`, and the navigation
between them. Route patterns are given as the code declares them.
Referenced from REPORT.md §5.7 (UI — Flow).

```mermaid
graph TD
  START(["App launch<br/>StartupViewModel resolves<br/>the start destination<br/><i>splash held until resolved</i>"])

  WEL["welcome"]
  LOGIN["login"]
  SIGNUP["signup?upgrade={upgrade}"]
  FORGOT["forgot_password?email={email}"]
  NAME["choose_display_name?mode={mode}"]
  ONB["onboarding"]

  HOME["home"]
  HIST["history"]
  LEAD["leaderboard"]
  PROF["profile"]

  CAL["calibration/{exerciseType}/{entryPoint}"]
  LIVE["live_workout/{exerciseType}<br/><i>squat, jumping jack</i>"]
  PUSH["pushup_workout<br/><i>camera, no argument</i>"]
  DET["session_detail/{sessionId}?postWorkout="]
  REPLAY["motion_replay/{sessionId}"]
  GHOST["ghost_duel"]
  FRIENDS["friends"]

  START --> WEL
  START -.->|"session restored"| HOME
  START -.->|"real account, no claimed name"| NAME
  START -.->|"never onboarded"| ONB

  WEL --> SIGNUP
  WEL --> LOGIN
  WEL -->|"continue as guest"| ONB
  LOGIN --> FORGOT
  LOGIN --> SIGNUP
  SIGNUP --> NAME
  NAME --> ONB
  NAME --> HOME
  ONB --> HOME

  HOME <-->|"bottom nav"| HIST
  HIST <-->|"bottom nav"| LEAD
  LEAD <-->|"bottom nav"| PROF
  HOME <-->|"bottom nav"| PROF

  HOME -->|"pick squat / jumping jack"| CAL
  CAL --> LIVE
  HOME -->|"pick push-up"| PUSH
  LIVE -->|"finish"| DET
  PUSH -->|"finish"| DET
  DET -->|"Review reps"| REPLAY
  HIST -->|"tap a session"| DET

  HOME --> GHOST
  LEAD --> GHOST
  GHOST --> LIVE
  HOME --> FRIENDS
  PROF --> FRIENDS
  PROF -->|"recalibrate"| CAL
  PROF -->|"guest upgrade"| SIGNUP
  PROF -->|"rename"| NAME
  PROF -->|"sign out"| WEL

  classDef auth fill:#eeeeee,stroke:#000;
  classDef tab fill:#ffffff,stroke:#000,stroke-width:3px;
  class WEL,LOGIN,SIGNUP,FORGOT,NAME,ONB auth;
  class HOME,HIST,LEAD,PROF tab;
```

Thick-bordered nodes are the four bottom-navigation tabs. Shaded nodes are the
pre-Home flow. Dashed edges are launch-time routing decided by `RepMateDestinations.afterAuth`,
which is the single home of that rule — used both after a sign-in and at launch for a
restored session.

Push-ups get their own route with no `{exerciseType}` argument because that screen is
push-ups only; there is nothing for an argument to select between.
