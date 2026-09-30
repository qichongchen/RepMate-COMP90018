
const fs = require("node:fs");
const { before, after, beforeEach, test } = require("node:test");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails
} = require("@firebase/rules-unit-testing");

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
