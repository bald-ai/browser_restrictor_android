import assert from "node:assert/strict";
import test from "node:test";
import { parseMultipartVideo, validateVerdict } from "../src/server.mjs";

test("extracts only the video/mp4 multipart field", () => {
  const boundary = "test-boundary";
  const bytes = Buffer.from("video bytes");
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="video"; filename="clip.mp4"\r\nContent-Type: video/mp4\r\n\r\n`),
    bytes,
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ]);
  assert.deepEqual(parseMultipartVideo(body, `multipart/form-data; boundary=${boundary}`), bytes);
});

test("validates the count-only model result", () => {
  assert.deepEqual(validateVerdict({ confidence: 0.9, repetition_count: 7, reason: "Visible cycles" }), {
    confidence: 0.9,
    repetition_count: 7,
    reason: "Visible cycles",
  });
  assert.throws(() => validateVerdict({ is_success: true, confidence: 1, repetition_count: 7, reason: "x" }));
});
