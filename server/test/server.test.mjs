import assert from "node:assert/strict";
import test from "node:test";
import {
  createServer,
  parseExerciseMultipart,
  parseMultipartVideo,
  validateVerdict,
} from "../src/server.mjs";
import {
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

test("extracts an exercise id and video for the generic endpoint", () => {
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
    parseExerciseMultipart(body, `multipart/form-data; boundary=${boundary}`),
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

test("catalog keeps video-dependent exercises disabled", () => {
  assert.equal(exerciseIsReady(getExerciseDefinition("push_up")), true);
  assert.equal(exerciseIsReady(getExerciseDefinition("plank")), false);
  assert.equal(getExerciseDefinition("plank").minimumSuccessValue, 25);
  assert.deepEqual(
    publicExerciseCatalog().map(({ id, metric, ready }) => ({ id, metric, ready })),
    [
      { id: "push_up", metric: "repetitions", ready: true },
      { id: "plank", metric: "hold_seconds", ready: false },
      { id: "outdoor_lat_pulldown", metric: "repetitions", ready: false },
      { id: "sit_up", metric: "repetitions", ready: false },
    ],
  );
});

test("health exposes readiness without prompts", async () => {
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
        minimum_success_value: 25,
        ready: false,
        pending: ["example_video", "recognition_prompt", "reward_policy"],
      },
    );
  });
});

test("generic endpoint rejects an unfinished exercise before model processing", async () => {
  await withServer(async (baseUrl) => {
    const form = new FormData();
    form.set("exercise_id", "plank");
    form.set("video", new Blob(["not inspected"], { type: "video/mp4" }), "clip.mp4");
    const response = await fetch(`${baseUrl}/v1/exercise-verify`, {
      method: "POST",
      headers: { Authorization: "Bearer test-secret" },
      body: form,
    });
    assert.equal(response.status, 409);
    assert.deepEqual(await response.json(), {
      error: "Exercise verification is not ready: example_video, recognition_prompt, reward_policy",
    });
  });
});
