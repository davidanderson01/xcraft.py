const { initializeTestEnvironment, assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

const PROJECT_ID = "demo-no-project";
let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: fs.readFileSync("firestore.rules", "utf8"),
      host: "127.0.0.1",
      port: 8085
    }
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write scripts", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users/user1/scripts").get());
  await assertFails(
    unauthDb.doc("users/user1/scripts/script1").set({
      userId: "user1",
      title: "Test",
      code: "print('hello')",
      category: "tool"
    })
  );
});

test("Authenticated user can read and write own scripts", async () => {
  const authDb = testEnv.authenticatedContext("user1").firestore();
  await assertSucceeds(
    authDb.doc("users/user1/scripts/script1").set({
      userId: "user1",
      title: "Test Script",
      code: "print('Xcraft Sovereign')",
      category: "agent"
    })
  );
  await assertSucceeds(authDb.collection("users/user1/scripts").get());
});

test("User cannot read or write another user's scripts", async () => {
  const authDb1 = testEnv.authenticatedContext("user1").firestore();
  const authDb2 = testEnv.authenticatedContext("user2").firestore();

  await assertSucceeds(
    authDb1.doc("users/user1/scripts/script1").set({
      userId: "user1",
      title: "Secret",
      code: "print('secret')",
      category: "security"
    })
  );

  await assertFails(authDb2.doc("users/user1/scripts/script1").get());
  await assertFails(
    authDb2.doc("users/user1/scripts/script2").set({
      userId: "user1",
      title: "Hack",
      code: "hack()",
      category: "tool"
    })
  );
});
