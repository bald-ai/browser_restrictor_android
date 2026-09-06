import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

// Exercises the deployed upload, FFmpeg, and AI path using synthetic video.
// This makes one paid model request and must never award exercise credit.
const endpoint = process.env.VERIFY_URL;
const secret = process.env.VERIFY_SHARED_SECRET;
assert(endpoint && secret, "VERIFY_URL and VERIFY_SHARED_SECRET are required");
const health = await fetch(new URL("/health", endpoint), { signal: AbortSignal.timeout(30_000) });
assert.equal(health.status, 200);
console.log("Health:", (await health.json()).status);
const unauthorized = await fetch(endpoint, { method: "POST", signal: AbortSignal.timeout(30_000) });
assert.equal(unauthorized.status, 401);
console.log("Unauthenticated upload: rejected");

const directory = await mkdtemp(path.join(os.tmpdir(), "verifier-smoke-"));
try {
  const file = path.join(directory, "test-pattern.mp4");
  execFileSync(process.env.FFMPEG_PATH || "ffmpeg", [
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10",
    "-t", "3", "-c:v", "libx264", "-threads", "1",
    "-pix_fmt", "yuv420p", "-movflags", "+faststart", file,
  ], { timeout: 30_000 });
  const body = new FormData();
  body.set("video", new Blob([await readFile(file)], { type: "video/mp4" }), "test-pattern.mp4");
  const started = performance.now();
  const response = await fetch(endpoint, {
    method: "POST",
    headers: { Authorization: `Bearer ${secret}` },
    body,
    signal: AbortSignal.timeout(205_000),
  });
  const result = await response.json();
  console.log("Verification:", response.status, JSON.stringify(result));
  assert.equal(response.status, 200);
  assert.equal(result.exercise_id, "unsupported");
  assert.equal(result.metric, "none");
  assert.equal(result.value, 0);
  console.log(`Synthetic video correctly earned no credit (${Math.round(performance.now() - started)}ms)`);
} finally {
  await rm(directory, { recursive: true, force: true });
}
