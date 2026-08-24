import crypto from "node:crypto";
import fs from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const MODEL = "google/gemini-3.7-flash";
const REQUIRED_REPETITIONS = 5;
const MAX_VIDEO_BYTES = 40 * 1024 * 1024;
const MAX_REQUEST_BYTES = MAX_VIDEO_BYTES + 1024 * 1024;
const MAX_SOURCE_SECONDS = 30.5;
const MODEL_SAMPLE_FPS = 4;
const FFMPEG_TIMEOUT_MS = 20_000;
const OPENROUTER_TIMEOUT_MS = 180_000;
const TEMP_PREFIX = "browser-restrictor-pushup-";

const systemPrompt = [
  "Count push-up repetitions in casual phone videos.",
  "Count each complete lowering-and-rising motion cycle once.",
  "The whole body does not need to be visible; continuous repeated motion of the visible head, shoulders, torso, or arms is sufficient.",
  "Tolerate imperfect framing, ordinary form, incomplete depth, brief occlusion, and camera shake.",
  "Ignore setup, repositioning, unrelated movement, cuts, loops, and motion before or after the exercise sequence.",
  "Identify the exercise segment, track its successive high and low body positions chronologically, and count each return from a low position to a high position as one cycle.",
  "Before answering, scan the full exercise segment a second time and reconcile the cycle count; do not estimate from elapsed time.",
  "Return zero only when no push-up-like repetition cycles are visible.",
].join(" ");

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function json(res, status, value, headers = {}) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Content-Length": body.length,
    "Cache-Control": "no-store",
    ...headers,
  });
  res.end(body);
}

async function readBoundedBody(req, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new HttpError(413, "Video upload is too large");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, size);
}

export function parseMultipartVideo(body, contentType) {
  const boundaryMatch = contentType?.match(/boundary=(?:"([^"]+)"|([^;]+))/i);
  const boundaryText = boundaryMatch?.[1] ?? boundaryMatch?.[2]?.trim();
  if (!boundaryText) throw new HttpError(400, "Expected multipart/form-data");

  const boundary = Buffer.from(`--${boundaryText}`);
  const delimiter = Buffer.from(`\r\n--${boundaryText}`);
  const headerSeparator = Buffer.from("\r\n\r\n");
  let cursor = body.indexOf(boundary);
  while (cursor !== -1) {
    cursor += boundary.length;
    if (body.subarray(cursor, cursor + 2).equals(Buffer.from("--"))) break;
    if (body.subarray(cursor, cursor + 2).equals(Buffer.from("\r\n"))) cursor += 2;
    const headerEnd = body.indexOf(headerSeparator, cursor);
    if (headerEnd === -1) break;
    const headers = body.subarray(cursor, headerEnd).toString("latin1");
    const dataStart = headerEnd + headerSeparator.length;
    const dataEnd = body.indexOf(delimiter, dataStart);
    if (dataEnd === -1) break;
    const isVideoField = /content-disposition:[^\r\n]*\bname="video"/i.test(headers);
    if (isVideoField) {
      if (!/^content-type:\s*video\/mp4\s*$/im.test(headers)) {
        throw new HttpError(400, "The video field must be video/mp4");
      }
      const video = body.subarray(dataStart, dataEnd);
      if (video.length === 0) throw new HttpError(400, "The video is empty");
      if (video.length > MAX_VIDEO_BYTES) throw new HttpError(413, "Video upload is too large");
      return video;
    }
    cursor = body.indexOf(boundary, dataEnd);
  }
  throw new HttpError(400, "Missing multipart video field");
}

async function runProcess(command, args, timeoutMs) {
  return await new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ["ignore", "pipe", "pipe"] });
    const stdout = [];
    const stderr = [];
    let stdoutSize = 0;
    let stderrSize = 0;
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      stdoutSize += chunk.length;
      if (stdoutSize <= 2 * 1024 * 1024) stdout.push(chunk);
    });
    child.stderr.on("data", (chunk) => {
      stderrSize += chunk.length;
      if (stderrSize <= 2 * 1024 * 1024) stderr.push(chunk);
    });
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      const result = {
        stdout: Buffer.concat(stdout).toString("utf8"),
        stderr: Buffer.concat(stderr).toString("utf8"),
      };
      if (timedOut) return reject(new HttpError(504, `${command} timed out`));
      if (code !== 0) return reject(new HttpError(400, `${command} rejected the video`));
      resolve(result);
    });
  });
}

async function inspectVideo(inputPath) {
  const result = await runProcess("ffprobe", [
    "-v", "error",
    "-show_entries", "format=duration,size:stream=codec_type,width,height",
    "-of", "json",
    inputPath,
  ], FFMPEG_TIMEOUT_MS);
  let parsed;
  try {
    parsed = JSON.parse(result.stdout);
  } catch {
    throw new HttpError(400, "Invalid MP4 metadata");
  }
  const duration = Number(parsed.format?.duration);
  const videoStream = parsed.streams?.find((stream) => stream.codec_type === "video");
  if (!videoStream || !Number.isFinite(duration) || duration <= 0) {
    throw new HttpError(400, "The MP4 has no usable video stream");
  }
  if (duration > MAX_SOURCE_SECONDS) throw new HttpError(400, "Video is longer than 30 seconds");
  return { duration, width: videoStream.width, height: videoStream.height };
}

async function prepareVideo(inputPath, outputPath) {
  const args = [
    "-hide_banner", "-loglevel", "error", "-y",
    "-i", inputPath,
    "-an", "-map_metadata", "-1",
    "-vf", `scale=360:-2,fps=${MODEL_SAMPLE_FPS},setpts=N/1/TB`,
    "-r", "1",
    "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
    "-pix_fmt", "yuv420p", "-movflags", "+faststart",
    outputPath,
  ];
  await runProcess("ffmpeg", args, FFMPEG_TIMEOUT_MS);
}

export function validateVerdict(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid verdict object");
  const keys = Object.keys(value).sort();
  if (keys.join(",") !== "confidence,reason,repetition_count") throw new Error("Unexpected verdict fields");
  if (!Number.isInteger(value.repetition_count) || value.repetition_count < 0 || value.repetition_count > 1000) {
    throw new Error("Invalid repetition_count");
  }
  if (typeof value.confidence !== "number" || value.confidence < 0 || value.confidence > 1) {
    throw new Error("Invalid confidence");
  }
  if (typeof value.reason !== "string" || value.reason.length > 2000) throw new Error("Invalid reason");
  return value;
}

async function callOpenRouter(videoPath, apiKey) {
  const video = await fs.readFile(videoPath);
  const payload = {
    model: MODEL,
    messages: [
      { role: "system", content: systemPrompt },
      {
        role: "user",
        content: [
          { type: "video_url", video_url: { url: `data:video/mp4;base64,${video.toString("base64")}` } },
          { type: "text", text: "Count the repetitions in the preceding video." },
        ],
      },
    ],
    reasoning: { effort: "low", exclude: true },
    max_tokens: 4096,
    response_format: {
      type: "json_schema",
      json_schema: {
        name: "pushup_count",
        strict: true,
        schema: {
          type: "object",
          properties: {
            confidence: { type: "number", minimum: 0, maximum: 1 },
            repetition_count: { type: "integer", minimum: 0 },
            reason: { type: "string" },
          },
          required: ["confidence", "repetition_count", "reason"],
          additionalProperties: false,
        },
      },
    },
    provider: { require_parameters: true, zdr: true },
  };

  let response;
  try {
    response = await fetch("https://openrouter.ai/api/v1/chat/completions", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/bald-ai/browser_restrictor_android",
        "X-Title": "browser-restrictor-pushup-verifier",
      },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(OPENROUTER_TIMEOUT_MS),
    });
  } catch (error) {
    if (error.name === "TimeoutError") throw new HttpError(504, "Model verification timed out");
    throw new HttpError(502, "Could not reach the verification model");
  }
  const text = await response.text();
  if (!response.ok) throw new HttpError(502, `Verification provider returned HTTP ${response.status}`);

  try {
    const envelope = JSON.parse(text);
    if (envelope.choices?.[0]?.finish_reason !== "stop") throw new Error("Incomplete model response");
    const verdict = validateVerdict(JSON.parse(envelope.choices[0].message.content));
    return {
      verdict,
      diagnostics: {
        provider: envelope.provider,
        prompt_tokens: envelope.usage?.prompt_tokens,
        completion_tokens: envelope.usage?.completion_tokens,
      },
    };
  } catch {
    throw new HttpError(502, "Verification model returned an invalid result");
  }
}

async function cleanupStaleDirectories() {
  const parent = os.tmpdir();
  const now = Date.now();
  for (const entry of await fs.readdir(parent, { withFileTypes: true })) {
    if (!entry.isDirectory() || !entry.name.startsWith(TEMP_PREFIX)) continue;
    const target = path.join(parent, entry.name);
    const stat = await fs.stat(target).catch(() => null);
    if (stat && now - stat.mtimeMs > 60 * 60 * 1000) {
      await fs.rm(target, { recursive: true, force: true });
    }
  }
}

export function createServer({ apiKey, sharedSecret }) {
  let busy = false;
  const server = http.createServer(async (req, res) => {
    const requestId = crypto.randomUUID();
    const startedAt = performance.now();
    if (req.method === "GET" && req.url === "/health") {
      return json(res, 200, { status: "ok", model: MODEL });
    }
    if (req.method !== "POST" || req.url !== "/v1/pushup-verify") {
      return json(res, 404, { error: "Not found" });
    }
    const authorization = req.headers.authorization;
    const expected = `Bearer ${sharedSecret}`;
    if (!authorization || authorization.length !== expected.length || !crypto.timingSafeEqual(Buffer.from(authorization), Buffer.from(expected))) {
      return json(res, 401, { error: "Unauthorized" });
    }
    if (busy) return json(res, 503, { error: "Verifier is busy" }, { "Retry-After": "5" });
    busy = true;
    let tempDirectory;
    try {
      const body = await readBoundedBody(req, MAX_REQUEST_BYTES);
      const video = parseMultipartVideo(body, req.headers["content-type"]);
      tempDirectory = await fs.mkdtemp(path.join(os.tmpdir(), TEMP_PREFIX));
      const inputPath = path.join(tempDirectory, "input.mp4");
      const outputPath = path.join(tempDirectory, "prepared.mp4");
      await fs.writeFile(inputPath, video, { mode: 0o600 });
      const metadata = await inspectVideo(inputPath);
      await prepareVideo(inputPath, outputPath);
      const modelStartedAt = performance.now();
      const { verdict, diagnostics } = await callOpenRouter(outputPath, apiKey);
      const modelMs = Math.round(performance.now() - modelStartedAt);
      const response = {
        is_success: verdict.repetition_count >= REQUIRED_REPETITIONS,
        repetition_count: verdict.repetition_count,
        confidence: verdict.confidence,
        reason: verdict.reason,
      };
      console.log(JSON.stringify({
        request_id: requestId,
        status: "ok",
        repetition_count: verdict.repetition_count,
        source_seconds: Number(metadata.duration.toFixed(3)),
        processed_seconds: Number(metadata.duration.toFixed(3)),
        model_ms: modelMs,
        total_ms: Math.round(performance.now() - startedAt),
        ...diagnostics,
      }));
      return json(res, 200, response);
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      console.error(JSON.stringify({
        request_id: requestId,
        status: "error",
        http_status: status,
        error: error.message,
        total_ms: Math.round(performance.now() - startedAt),
      }));
      return json(res, status, { error: status === 500 ? "Internal verifier error" : error.message });
    } finally {
      if (tempDirectory) await fs.rm(tempDirectory, { recursive: true, force: true }).catch(() => {});
      busy = false;
    }
  });
  server.headersTimeout = 10_000;
  server.requestTimeout = 205_000;
  return server;
}

async function main() {
  const apiKey = process.env.OPENROUTER_API_KEY;
  const sharedSecret = process.env.VERIFY_SHARED_SECRET;
  if (!apiKey || !sharedSecret) throw new Error("OPENROUTER_API_KEY and VERIFY_SHARED_SECRET are required");
  await cleanupStaleDirectories();
  const host = process.env.VERIFY_HOST ?? "127.0.0.1";
  const port = Number(process.env.VERIFY_PORT ?? "8787");
  const server = createServer({ apiKey, sharedSecret });
  server.listen(port, host, () => console.log(JSON.stringify({ status: "listening", host, port, model: MODEL })));
}

if (path.resolve(process.argv[1] ?? "") === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}
