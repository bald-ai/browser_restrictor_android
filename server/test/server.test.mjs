import assert from "node:assert/strict";
import test from "node:test";
import {
  createServer,
  normalizeClassifierVerdict,
  parseExerciseMultipart,
  parseMultipartVideo,
  validateClassifierVerdict,
  validateVerdict,
} from "../src/server.mjs";
import {
  CLASSIFIER_EXERCISE_IDS,
  classifierPrompt,
  exerciseIsReady,
  getExerciseDefinition,
  publicExerciseCatalog,
} from "../src/exercises.mjs";

async function withServer(run) {
  const server = createServer({ apiKey: "unused-test-key", sharedSecret: "test-secret" });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const address = server.address();
    await run(`http://127.0.0.1:${address.port}`);
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

function multipartBody(boundary, fields) {
  const chunks = [];
  for (const field of fields) {
    chunks.push(Buffer.from(`--${boundary}\r\n${field.headers}\r\n\r\n`));
    chunks.push(Buffer.isBuffer(field.value) ? field.value : Buffer.from(field.value));
    chunks.push(Buffer.from("\r\n"));
  }
  chunks.push(Buffer.from(`--${boundary}--\r\n`));
  return Buffer.concat(chunks);
}

test("extracts only the video/mp4 multipart field", () => {
  const boundary = "test-boundary";
  const bytes = Buffer.from("video bytes");
  const body = multipartBody(boundary, [{
    headers: "Content-Disposition: form-data; name=\"video\"; filename=\"clip.mp4\"\r\nContent-Type: video/mp4",
    value: bytes,
  }]);
  assert.deepEqual(parseMultipartVideo(body, `multipart/form-data; boundary=${boundary}`), bytes);
});

test("generic endpoint accepts a video without an exercise picker field", () => {
  const boundary = "exercise-boundary";
  const bytes = Buffer.from("video bytes");
  const body = multipartBody(boundary, [{
    headers: "Content-Disposition: form-data; name=\"video\"; filename=\"clip.mp4\"\r\nContent-Type: video/mp4",
    value: bytes,
  }]);

  assert.deepEqual(
    parseExerciseMultipart(body, `multipart/form-data; boundary=${boundary}`, { requireExerciseId: false }),
    { exerciseId: null, video: bytes },
  );
});

test("generic parser ignores a supplied exercise_id instead of selecting a prompt", () => {
  const boundary = "exercise-boundary";
  const bytes = Buffer.from("video bytes");
  const body = multipartBody(boundary, [
    {
      headers: "Content-Disposition: form-data; name=\"exercise_id\"",
      value: "plank",
    },
    {
      headers: "Content-Disposition: form-data; name=\"video\"; filename=\"clip.mp4\"\r\nContent-Type: video/mp4",
      value: bytes,
    },
  ]);

  assert.deepEqual(
    parseExerciseMultipart(body, `multipart/form-data; boundary=${boundary}`, { requireExerciseId: false }),
    { exerciseId: "plank", video: bytes },
  );
});

test("validates the count-only model result", () => {
  assert.deepEqual(validateVerdict({ confidence: 0.9, repetition_count: 7, reason: "Visible cycles" }), {
    confidence: 0.9,
    repetition_count: 7,
    reason: "Visible cycles",
  });
  assert.throws(() => validateVerdict({ is_success: true, confidence: 1, repetition_count: 7, reason: "x" }));
});

test("validates duration without treating seconds as repetitions", () => {
  const plank = getExerciseDefinition("plank");
  assert.deepEqual(validateVerdict({ confidence: 0.8, hold_seconds: 25, reason: "Stable hold" }, plank), {
    confidence: 0.8,
    hold_seconds: 25,
    reason: "Stable hold",
  });
  assert.throws(() => validateVerdict({ confidence: 0.8, repetition_count: 25, reason: "x" }, plank));
});

test("classifier verdict is typed and fail-closed for mixed or unrelated clips", () => {
  assert.deepEqual(validateClassifierVerdict({
    exercise_id: "plank",
    metric: "hold_seconds",
    value: 18,
    reason: "Continuous hold",
  }), {
    exercise_id: "plank",
    metric: "hold_seconds",
    value: 18,
    reason: "Continuous hold",
  });
  assert.deepEqual(validateClassifierVerdict({
    exercise_id: "outdoor_lat_pulldown",
    metric: "repetitions",
    value: 10,
    reason: "Seated pull cycles",
  }).value, 10);
  assert.deepEqual(validateClassifierVerdict({
    exercise_id: "unsupported",
    metric: "none",
    value: 0,
    reason: "Mixed motions",
  }).exercise_id, "unsupported");
  assert.throws(() => validateClassifierVerdict({
    exercise_id: "plank",
    metric: "repetitions",
    value: 18,
    reason: "x",
  }));
  assert.throws(() => validateClassifierVerdict({
    exercise_id: "unsupported",
    metric: "none",
    value: 4,
    reason: "x",
  }));
  assert.throws(() => validateClassifierVerdict({
    exercise_id: "plank",
    metric: "hold_seconds",
    value: 18,
    confidence: 0.9,
    reason: "x",
  }));
  assert.throws(() => validateClassifierVerdict({
    exercise_id: "plank",
    metric: "hold_seconds",
    value: 18,
    is_success: true,
    reason: "x",
  }));
});

test("server converts densified plank time while leaving repetition counts unchanged", () => {
  assert.equal(normalizeClassifierVerdict({
    exercise_id: "plank",
    metric: "hold_seconds",
    value: 73,
    reason: "Held from playback second 8 to 81",
  }).value, 18);
  assert.equal(normalizeClassifierVerdict({
    exercise_id: "outdoor_lat_pulldown",
    metric: "repetitions",
    value: 10,
    reason: "Ten complete cycles",
  }).value, 10);
});

test("classifier prompt keeps business thresholds out of the model", () => {
  assert.match(classifierPrompt, /push_up/);
  assert.match(classifierPrompt, /outdoor_lat_pulldown/);
  assert.doesNotMatch(classifierPrompt, /\b5 push-ups\b/);
  assert.doesNotMatch(classifierPrompt, /\b18\b/);
  assert.doesNotMatch(classifierPrompt, /\b10 machine\b/);
  assert.doesNotMatch(classifierPrompt, /\+3/);
  assert.doesNotMatch(classifierPrompt, /04:00/);
  assert.match(classifierPrompt, /do not convert it to original time/i);
  assert.equal(CLASSIFIER_EXERCISE_IDS.includes("sit_up"), false);
});

test("catalog marks credited exercises ready and keeps sit-ups disabled", () => {
  assert.equal(exerciseIsReady(getExerciseDefinition("push_up")), true);
  assert.equal(exerciseIsReady(getExerciseDefinition("plank")), true);
  assert.equal(exerciseIsReady(getExerciseDefinition("outdoor_lat_pulldown")), true);
  assert.equal(exerciseIsReady(getExerciseDefinition("sit_up")), false);
  assert.equal(getExerciseDefinition("plank").unitsPerSet, 18);
  assert.equal(getExerciseDefinition("outdoor_lat_pulldown").unitsPerSet, 10);
  assert.deepEqual(
    publicExerciseCatalog().map(({ id, metric, ready, units_per_set }) => ({
      id,
      metric,
      ready,
      units_per_set,
    })),
    [
      { id: "push_up", metric: "repetitions", ready: true, units_per_set: 5 },
      { id: "plank", metric: "hold_seconds", ready: true, units_per_set: 18 },
      { id: "outdoor_lat_pulldown", metric: "repetitions", ready: true, units_per_set: 10 },
      { id: "sit_up", metric: "repetitions", ready: false, units_per_set: null },
    ],
  );
});

test("health exposes readiness without prompts or success flags", async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/health`);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.exercises.find(({ id }) => id === "push_up").ready, true);
    assert.deepEqual(
      body.exercises.find(({ id }) => id === "plank"),
      {
        id: "plank",
        display_name: "Plank",
        metric: "hold_seconds",
        units_per_set: 18,
        ready: true,
        pending: [],
      },
    );
    assert.equal("minimum_success_value" in body.exercises.find(({ id }) => id === "plank"), false);
  });
});

test("legacy endpoint still rejects a non-push-up exercise_id before model processing", async () => {
  await withServer(async (baseUrl) => {
    const form = new FormData();
    form.set("exercise_id", "plank");
    form.set("video", new Blob(["not inspected"], { type: "video/mp4" }), "clip.mp4");
    const response = await fetch(`${baseUrl}/v1/pushup-verify`, {
      method: "POST",
      headers: { Authorization: "Bearer test-secret" },
      body: form,
    });
    assert.equal(response.status, 400);
    assert.deepEqual(await response.json(), {
      error: "The legacy endpoint accepts only push_up",
    });
  });
});
