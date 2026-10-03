
const assert = require("node:assert/strict");
const fs = require("node:fs");
const { before, after, beforeEach, test } = require("node:test");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails
} = require("@firebase/rules-unit-testing");
const firebase = require("firebase/compat/app");
require("firebase/compat/firestore");

let env;

const scorePath = "users/alice/ghostScores/SQUAT";

const validScore = {
  exercise: "SQUAT",
  averageScore: 8.5,
  repCount: 12,
  startedAt: 1790200000000
};

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "repmate-rules-test",
    firestore: {
      host: "127.0.0.1",
      port: 8080,
      rules: fs.readFileSync("firestore.rules", "utf8")
    }
  });
});

beforeEach(async () => {
  await env.clearFirestore();
});

after(async () => {
  await env.cleanup();
});

test("Owner can publish a valid score", async () => {
  const db = env.authenticatedContext("alice").firestore();

  await assertSucceeds(
    db.doc(scorePath).set(validScore)
  );
});

test("Signed-in user can read a shared score", async () => {
  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc(scorePath).set(validScore);
  });

  const db = env.authenticatedContext("bob").firestore();

  await assertSucceeds(db.doc(scorePath).get());
});

test("Another user cannot modify a score", async () => {
  const db = env.authenticatedContext("bob").firestore();

  await assertFails(
    db.doc(scorePath).set(validScore)
  );
});

test("Unauthenticated user cannot read a score", async () => {
  const db = env.unauthenticatedContext().firestore();

  await assertFails(db.doc(scorePath).get());
});

test("Scores above 10 are rejected", async () => {
  const db = env.authenticatedContext("alice").firestore();

  await assertFails(
    db.doc(scorePath).set({
      ...validScore,
      averageScore: 11
    })
  );
});

test("Zero-rep scores are eliminated", async () => {
  const db = env.authenticatedContext("alice").firestore();

  await assertFails(
    db.doc(scorePath).set({
      ...validScore,
      repCount: 0
    })
  );
});

test("Other users cannot list shared scores", async () => {
  const db = env.authenticatedContext("bob").firestore();

  await assertFails(
    db.collection("users/alice/ghostScores").get()
  );
});

test("Owner can update their own score", async () => {
  const db = env.authenticatedContext("alice").firestore();

  await assertSucceeds(
    db.doc(scorePath).set(validScore)
  );

  await assertSucceeds(
    db.doc(scorePath).update({
      averageScore: 9.0,
      repCount: 15
    })
  );
});


test("Owner can delete their own score", async () => {
  const db = env.authenticatedContext("alice").firestore();

  await assertSucceeds(
    db.doc(scorePath).set(validScore)
  );

  await assertSucceeds(
    db.doc(scorePath).delete()
  );
});

// ---------------------------------------------------------------------------------------------
// Unique display names (usernames/{name}) and the tightened users/{uid} update rule.
// ---------------------------------------------------------------------------------------------

// The value the app writes for every timestamp; the rules require createdAt == request.time.
const serverTime = () => firebase.firestore.FieldValue.serverTimestamp();

// A signed-in user with a real (non-guest) provider, and an anonymous guest.
const realUser = (uid, provider = "google.com") =>
  env
    .authenticatedContext(uid, { firebase: { sign_in_provider: provider } })
    .firestore();
const guestUser = uid =>
  env
    .authenticatedContext(uid, { firebase: { sign_in_provider: "anonymous" } })
    .firestore();

const nameId = name => name.toLowerCase();

// Profiles/claims are seeded with rules disabled so each test starts from a known state.
async function seedProfile(uid, displayName = "RepMate User") {
  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc(`users/${uid}`).set({
      displayName,
      createdAt: firebase.firestore.Timestamp.now()
    });
  });
}

async function seedClaim(name, uid) {
  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc(`usernames/${nameId(name)}`).set({
      uid,
      createdAt: firebase.firestore.Timestamp.now()
    });
  });
}

// The batch the app sends to claim a name: the claim plus the matching profile update.
function claimBatch(db, uid, name, { updateProfile = true } = {}) {
  const batch = db.batch();
  batch.set(db.doc(`usernames/${nameId(name)}`), {
    uid,
    createdAt: serverTime()
  });
  if (updateProfile) {
    batch.update(db.doc(`users/${uid}`), { displayName: name });
  }
  return batch;
}

// ---- claiming ----

test("Claiming a free name with the matching profile update works", async () => {
  await seedProfile("alice");
  const db = realUser("alice");

  await assertSucceeds(claimBatch(db, "alice", "Cool_Guy 99").commit());
});

test("Email/password accounts can claim too", async () => {
  await seedProfile("alice");
  const db = realUser("alice", "password");

  await assertSucceeds(claimBatch(db, "alice", "Alice").commit());
});

test("Claiming the name the profile already has works without a profile update", async () => {
  // e.g. a Google display name the profile was created with.
  await seedProfile("alice", "Alice Smith");
  const db = realUser("alice");

  await assertSucceeds(
    claimBatch(db, "alice", "Alice Smith", { updateProfile: false }).commit()
  );
});

test("A name is taken case-insensitively", async () => {
  await seedProfile("alice");
  await seedProfile("bob");
  await seedClaim("CoolGuy", "bob");
  const db = realUser("alice");

  for (const attempt of ["coolguy", "CoolGuy", "COOLGUY"]) {
    await assertFails(claimBatch(db, "alice", attempt).commit());
  }
});

test("Claiming a name for another UID fails", async () => {
  // alice's profile already carries the name, so the only thing wrong with this claim is that it
  // names bob as the owner. Only the uid == request.auth.uid check can stop it.
  await seedProfile("alice", "Bobby");
  await seedProfile("bob");
  const db = realUser("alice");

  await assertFails(
    db.doc("usernames/bobby").set({ uid: "bob", createdAt: serverTime() })
  );
});

test("Claiming a name in a batch that renames another user's profile fails", async () => {
  await seedProfile("alice");
  await seedProfile("bob");
  const db = realUser("alice");

  const batch = db.batch();
  batch.set(db.doc("usernames/bobby"), { uid: "bob", createdAt: serverTime() });
  batch.update(db.doc("users/bob"), { displayName: "Bobby" });

  await assertFails(batch.commit());
});

test("An anonymous user cannot claim a name", async () => {
  await seedProfile("guest-uid");
  const db = guestUser("guest-uid");

  await assertFails(claimBatch(db, "guest-uid", "Visitor").commit());
});

test("Claiming without a profile document fails", async () => {
  const db = realUser("alice");

  await assertFails(
    claimBatch(db, "alice", "Alice", { updateProfile: false }).commit()
  );
});

test("A claim whose id does not match the profile's new name fails", async () => {
  await seedProfile("alice");
  const db = realUser("alice");

  const batch = db.batch();
  batch.set(db.doc("usernames/someone_else"), {
    uid: "alice",
    createdAt: serverTime()
  });
  batch.update(db.doc("users/alice"), { displayName: "Alice" });

  await assertFails(batch.commit());
});

// ---- name format ----

const invalidNames = {
  "too short (2)": "ab",
  "too long (21)": "a".repeat(21),
  "leading space": " alice",
  "trailing space": "alice ",
  "double space": "al  ice",
  "hyphen": "al-ice",
  "dot": "al.ice",
  "apostrophe": "al'ice",
  "emoji": "alice\u{1F600}",
  "accented letter": "Jos\u00E9",
  "only spaces between": "   "
};

for (const [label, name] of Object.entries(invalidNames)) {
  test(`Invalid name is rejected: ${label}`, async () => {
    await seedProfile("alice");
    const db = realUser("alice");

    await assertFails(claimBatch(db, "alice", name).commit());
  });
}

test("Boundary lengths 3 and 20 are accepted", async () => {
  await seedProfile("alice");
  await seedProfile("bob");

  await assertSucceeds(claimBatch(realUser("alice"), "alice", "abc").commit());
  await assertSucceeds(
    claimBatch(realUser("bob"), "bob", "b".repeat(20)).commit()
  );
});

test("A name with single spaces between words is accepted", async () => {
  await seedProfile("alice");

  await assertSucceeds(
    claimBatch(realUser("alice"), "alice", "The Real Alice").commit()
  );
});

// ---- reserved words and the guest prefix ----

for (const name of [
  "admin",
  "Admin",
  "REPMATE",
  "support",
  "Moderator",
  "guest",
  "Guest123",
  "guest_7"
]) {
  test(`Reserved or guest-prefixed name is rejected: ${name}`, async () => {
    await seedProfile("alice");
    const db = realUser("alice");

    await assertFails(claimBatch(db, "alice", name).commit());
  });
}

test("Reserved words are matched whole, so admin2 and the_admin are fine", async () => {
  await seedProfile("alice");
  await seedProfile("bob");

  await assertSucceeds(claimBatch(realUser("alice"), "alice", "admin2").commit());
  await assertSucceeds(claimBatch(realUser("bob"), "bob", "the_admin").commit());
});

// ---- look-alike Unicode names (Kelvin sign U+212A) ----
// A Unicode-aware lower() would turn the Kelvin sign into a plain "k", so "\u212Aelvin" would map
// to the ASCII id "kelvin". The rules therefore also check the ORIGINAL name against the ASCII
// pattern. NOTE: the local emulator's lower() is ASCII-only (it does not even lowercase "\u00C9"),
// so these two tests pass with or without that extra check here; they pin the required behaviour
// in case production's lower() is Unicode-aware.

const kelvinLookalike = "\u212Aelvin";

test("Look-alike Unicode name is rejected when creating a username", async () => {
  await seedProfile("alice");
  const db = realUser("alice");

  // The profile carries the look-alike; it lowercases to the ASCII id "kelvin".
  const batch = db.batch();
  batch.set(db.doc("usernames/kelvin"), { uid: "alice", createdAt: serverTime() });
  batch.update(db.doc("users/alice"), { displayName: kelvinLookalike });

  await assertFails(batch.commit());
});

test("Look-alike Unicode name is rejected when changing displayName, even for an owned id", async () => {
  await seedProfile("alice", "Kelvin");
  await seedClaim("Kelvin", "alice");
  const db = realUser("alice");

  // alice really owns usernames/kelvin, so ownership alone would allow this. Only the format
  // check on the new displayName stops the look-alike.
  await assertFails(
    db.doc("users/alice").update({ displayName: kelvinLookalike })
  );
});

// ---- createdAt and shape ----

test("A back-dated createdAt is rejected", async () => {
  await seedProfile("alice");
  const db = realUser("alice");

  const batch = db.batch();
  batch.set(db.doc("usernames/alice"), {
    uid: "alice",
    createdAt: firebase.firestore.Timestamp.fromMillis(1000)
  });
  batch.update(db.doc("users/alice"), { displayName: "Alice" });

  await assertFails(batch.commit());
});

test("A non-timestamp createdAt is rejected", async () => {
  await seedProfile("alice");
  const db = realUser("alice");

  const batch = db.batch();
  batch.set(db.doc("usernames/alice"), { uid: "alice", createdAt: "now" });
  batch.update(db.doc("users/alice"), { displayName: "Alice" });

  await assertFails(batch.commit());
});

test("A username document with extra or missing fields is rejected", async () => {
  await seedProfile("alice");
  const db = realUser("alice");

  const extra = db.batch();
  extra.set(db.doc("usernames/alice"), {
    uid: "alice",
    createdAt: serverTime(),
    role: "admin"
  });
  extra.update(db.doc("users/alice"), { displayName: "Alice" });
  await assertFails(extra.commit());

  const missing = db.batch();
  missing.set(db.doc("usernames/alice"), { uid: "alice" });
  missing.update(db.doc("users/alice"), { displayName: "Alice" });
  await assertFails(missing.commit());
});

test("A username document cannot be updated", async () => {
  await seedProfile("alice", "Alice");
  await seedClaim("Alice", "alice");
  const db = realUser("alice");

  await assertFails(
    db.doc("usernames/alice").update({ uid: "bob" })
  );
});

// ---- renaming, releasing and stealing ----

test("A rename batch (delete old, create new, update profile) works", async () => {
  await seedProfile("alice", "OldName");
  await seedClaim("OldName", "alice");
  const db = realUser("alice");

  const batch = db.batch();
  batch.delete(db.doc("usernames/oldname"));
  batch.set(db.doc("usernames/newname"), {
    uid: "alice",
    createdAt: serverTime()
  });
  batch.update(db.doc("users/alice"), { displayName: "NewName" });

  await assertSucceeds(batch.commit());
});

// ---- no hoarding ----

test("Renaming without releasing the old name (hoarding) fails", async () => {
  await seedProfile("alice", "OldName");
  await seedClaim("OldName", "alice");

  await assertFails(claimBatch(realUser("alice"), "alice", "NewName").commit());
});

test("Hoarding several names one rename at a time is impossible", async () => {
  await seedProfile("alice", "Name_One");
  await seedClaim("Name_One", "alice");
  const db = realUser("alice");

  await assertFails(claimBatch(db, "alice", "Name_Two").commit());
  // The claim did not land, so the old one is still the only name she holds.
  await assertSucceeds(db.doc("usernames/name_one").get());
  const free = await db.doc("usernames/name_two").get();
  assert.equal(free.exists, false);
});

test("A released name becomes available to someone else", async () => {
  await seedProfile("alice", "OldName");
  await seedProfile("bob");
  await seedClaim("OldName", "alice");

  const db = realUser("alice");
  await assertFails(db.doc("usernames/oldname").delete()); // releasing alone, without renaming, is refused

  const batch = db.batch();
  batch.delete(db.doc("usernames/oldname"));
  batch.set(db.doc("usernames/newname"), { uid: "alice", createdAt: serverTime() });
  batch.update(db.doc("users/alice"), { displayName: "NewName" });
  await assertSucceeds(batch.commit());

  await assertSucceeds(claimBatch(realUser("bob"), "bob", "OldName").commit());
});

test("Hoarding by renaming the profile while another claim is created elsewhere fails", async () => {
  // alice owns "first"; she claims "second" and renames, but only deletes an unrelated document.
  await seedProfile("alice", "First");
  await seedClaim("First", "alice");
  await seedClaim("Unrelated", "alice");
  const db = realUser("alice");

  const batch = db.batch();
  batch.delete(db.doc("usernames/unrelated"));
  batch.set(db.doc("usernames/second"), { uid: "alice", createdAt: serverTime() });
  batch.update(db.doc("users/alice"), { displayName: "Second" });

  await assertFails(batch.commit());
});

test("A legacy unclaimed name can be renamed away from", async () => {
  // The default name has no usernames document, so there is nothing to release.
  await seedProfile("alice", "RepMate User");

  await assertSucceeds(claimBatch(realUser("alice"), "alice", "Alice").commit());
});

test("A duplicate legacy name owned by someone else can still be renamed away from", async () => {
  // Two people got the same Google display name; bob claimed it first. alice never did, so she
  // holds nothing and must not be locked out just because the document exists.
  await seedProfile("alice", "Bobby");
  await seedProfile("bob", "Bobby");
  await seedClaim("Bobby", "bob");

  await assertSucceeds(claimBatch(realUser("alice"), "alice", "Alice").commit());
  // bob keeps his claim.
  const kept = await realUser("bob").doc("usernames/bobby").get();
  assert.equal(kept.data().uid, "bob");
});

test("A legacy name that cannot be claimed (or even used in a path) can be renamed away from", async () => {
  await seedProfile("alice", "Jos\u00E9");
  await seedProfile("bob", "a/b");

  await assertSucceeds(claimBatch(realUser("alice"), "alice", "Jose_2").commit());
  await assertSucceeds(claimBatch(realUser("bob"), "bob", "Bob_2").commit());
});

test("A case-only rename needs no username write and keeps the claim", async () => {
  await seedProfile("alice", "alice");
  await seedClaim("alice", "alice");
  const db = realUser("alice");

  await assertSucceeds(db.doc("users/alice").update({ displayName: "Alice" }));
  await assertSucceeds(db.doc("users/alice").update({ displayName: "ALICE" }));

  // Same id, same document, still hers: nothing had to be deleted or recreated.
  const claim = await db.doc("usernames/alice").get();
  assert.equal(claim.data().uid, "alice");
});

test("A case-only change to a name you do not own still fails", async () => {
  await seedProfile("alice", "Bob");
  await seedProfile("bob", "bob");
  await seedClaim("bob", "bob");

  await assertFails(realUser("alice").doc("users/alice").update({ displayName: "bob" }));
});

test("Deleting a name without renaming fails", async () => {
  await seedProfile("alice", "Alice");
  await seedClaim("Alice", "alice");

  await assertFails(realUser("alice").doc("usernames/alice").delete());
});

test("Deleting someone else's name fails", async () => {
  await seedProfile("alice", "Alice");
  await seedProfile("bob", "Bobby");
  await seedClaim("Alice", "alice");

  // bob's profile no longer maps to "alice", but the name is not his to release.
  await assertFails(realUser("bob").doc("usernames/alice").delete());
});

test("Changing displayName to a name you do not own fails", async () => {
  await seedProfile("alice");
  await seedProfile("bob", "Bobby");
  await seedClaim("Bobby", "bob");
  const db = realUser("alice");

  await assertFails(db.doc("users/alice").update({ displayName: "Bobby" }));
  // A free name that alice has not claimed is refused too.
  await assertFails(db.doc("users/alice").update({ displayName: "Unclaimed" }));
});

test("An update that leaves displayName unchanged is still allowed", async () => {
  await seedProfile("alice", "RepMate User");

  await assertSucceeds(
    realUser("alice").doc("users/alice").update({ displayName: "RepMate User" })
  );
});

test("A profile update cannot touch other fields", async () => {
  await seedProfile("alice", "Alice");
  await seedClaim("Alice", "alice");

  await assertFails(
    realUser("alice").doc("users/alice").update({ role: "admin" })
  );
});

// ---- reading ----

test("Signed-in users can get a username document", async () => {
  await seedClaim("Alice", "alice");

  await assertSucceeds(realUser("bob").doc("usernames/alice").get());
  await assertSucceeds(guestUser("guest-uid").doc("usernames/alice").get());
  // Checking a free name is also a plain get.
  await assertSucceeds(realUser("bob").doc("usernames/free_name").get());
});

test("Signed-out users cannot get a username document", async () => {
  await seedClaim("Alice", "alice");
  const db = env.unauthenticatedContext().firestore();

  await assertFails(db.doc("usernames/alice").get());
});

test("Usernames cannot be listed", async () => {
  await seedClaim("Alice", "alice");

  await assertFails(realUser("bob").collection("usernames").get());
  await assertFails(guestUser("guest-uid").collection("usernames").get());
});

// ---- existing behaviour, unchanged ----

test("Profile creation with the default name and a timestamp still works", async () => {
  const db = realUser("alice");

  await assertSucceeds(
    db.doc("users/alice").set({
      displayName: "RepMate User",
      createdAt: serverTime()
    })
  );
});

test("Guests can still create their default profile", async () => {
  await assertSucceeds(
    guestUser("guest-uid").doc("users/guest-uid").set({
      displayName: "RepMate User",
      createdAt: serverTime()
    })
  );
});

test("Profile creation for another user or with extra fields still fails", async () => {
  const db = realUser("alice");

  await assertFails(
    db.doc("users/bob").set({ displayName: "RepMate User", createdAt: serverTime() })
  );
  await assertFails(
    db.doc("users/alice").set({
      displayName: "RepMate User",
      createdAt: serverTime(),
      role: "admin"
    })
  );
});

test("Profiles can be read by a single get but not listed or deleted", async () => {
  await seedProfile("alice");
  const bob = realUser("bob");

  await assertSucceeds(bob.doc("users/alice").get());
  await assertFails(bob.collection("users").get());
  await assertFails(realUser("alice").doc("users/alice").delete());
  await assertFails(env.unauthenticatedContext().firestore().doc("users/alice").get());
});

test("Workout sessions are readable and writable only by their owner", async () => {
  const path = "users/alice/workoutSessions/s1";
  const collectionPath = "users/alice/workoutSessions";
  const data = { id: "s1", exercise: "SQUAT", repCount: 3 };

  await assertSucceeds(realUser("alice").doc(path).set(data));
  await assertSucceeds(realUser("alice").doc(path).get());
  // Owner can list their own workout sessions.
  await assertSucceeds(
      realUser("alice").collection(collectionPath).get()
  );

  // Another signed-in user cannot access or list Alice's workout sessions.
  await assertFails(realUser("bob").doc(path).get());
  await assertFails(realUser("bob").doc(path).set(data));
  await assertFails(
      realUser("bob").collection(collectionPath).get()
  );

  // An anonymous guest cannot access or list another user's workout sessions.
  await assertFails(
      guestUser("guest-uid").doc(path).get()
  );
  await assertFails(
      guestUser("guest-uid").collection(collectionPath).get()
  );

  // A completely unauthenticated user cannot access the workout session.
  await assertFails(
      env.unauthenticatedContext().firestore().doc(path).get()
  );
});

test("Friends are readable and writable only by their owner", async () => {
  const path = "users/alice/friends/bob";
  const data = { displayName: "Bob" };

  await assertSucceeds(realUser("alice").doc(path).set(data));
  await assertSucceeds(realUser("alice").doc(path).get());
  await assertFails(realUser("bob").doc(path).get());
  await assertFails(realUser("bob").doc(path).set(data));
});

test("The leaderboard is readable when signed in and never writable", async () => {
  await env.withSecurityRulesDisabled(async context => {
    await context.firestore().doc("leaderboard/alice").set({ score: 1 });
  });

  await assertSucceeds(realUser("bob").doc("leaderboard/alice").get());
  await assertFails(realUser("alice").doc("leaderboard/alice").set({ score: 99 }));
  await assertFails(
    env.unauthenticatedContext().firestore().doc("leaderboard/alice").get()
  );
});
