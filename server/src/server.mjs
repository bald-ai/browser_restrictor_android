import crypto from "node:crypto";
import fs from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { listenAddress } from "./runtime-config.mjs";
import {
  CLASSIFIER_EXERCISE_IDS,
  DENSIFY_FACTOR,
  classifierPrompt,
  classifierUserPrompt,
  expectedMetricForExercise,
  exerciseIsReady,
  getExerciseDefinition,
  publicExerciseCatalog,
} from "./exercises.mjs";

const MODEL = "google/gemini-3.8-flash";
const MAX_VIDEO_BYTES = 75 * 1024 * 1024;
const MAX_REQUEST_BYTES = MAX_VIDEO_BYTES + 1024 * 1024;
const MAX_SOURCE_SECONDS = 30.5;
const MODEL_SAMPLE_FPS = 4;
const FFMPEG_TIMEOUT_MS = 20_000;
const OPENROUTER_TIMEOUT_MS = 180_000;
const TEMP_PREFIX = "browser-restrictor-exercise-";
const LEGACY_TEMP_PREFIX = "browser-restrictor-pushup-";

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

function parseMultipartParts(body, contentType) {
  const boundaryMatch = contentType?.match(/boundary=(?:"([^"]+)"|([^;]+))/i);
  const boundaryText = boundaryMatch?.[1] ?? boundaryMatch?.[2]?.trim();
  if (!boundaryText) throw new HttpError(400, "Expected multipart/form-data");

  const boundary = Buffer.from(`--${boundaryText}`);
  const delimiter = Buffer.from(`\r\n--${boundaryText}`);
  const headerSeparator = Buffer.from("\r\n\r\n");
  const parts = [];
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
    const name = headers.match(/content-disposition:[^\r\n]*\bname="([^"]+)"/i)?.[1];
    if (name) {
      parts.push({ name, headers, value: body.subarray(dataStart, dataEnd) });
    }
    cursor = body.indexOf(boundary, dataEnd);
  }
  return parts;
}

export function parseExerciseMultipart(body, contentType, { defaultExerciseId = null, requireExerciseId = true } = {}) {
  const parts = parseMultipartParts(body, contentType);
  const videos = parts.filter((part) => part.name === "video");
  if (videos.length !== 1) throw new HttpError(400, "Expected one multipart video field");
  const videoPart = videos[0];
  if (!/^content-type:\s*video\/mp4\s*$/im.test(videoPart.headers)) {
    throw new HttpError(400, "The video field must be video/mp4");
  }
  if (videoPart.value.length === 0) throw new HttpError(400, "The video is empty");
  if (videoPart.value.length > MAX_VIDEO_BYTES) throw new HttpError(413, "Video upload is too large");

  const exerciseParts = parts.filter((part) => part.name === "exercise_id");
  if (exerciseParts.length > 1) throw new HttpError(400, "Expected at most one exercise_id field");
  const suppliedExerciseId = exerciseParts[0]?.value.toString("utf8").trim();
  if (suppliedExerciseId !== undefined && !/^[a-z][a-z0-9_]{0,63}$/.test(suppliedExerciseId)) {
    throw new HttpError(400, "Invalid exercise_id");
  }
  const exerciseId = suppliedExerciseId || defaultExerciseId;
  if (requireExerciseId && !exerciseId) throw new HttpError(400, "Missing multipart exercise_id field");
  return { exerciseId: exerciseId ?? null, video: videoPart.value };
}

export function parseMultipartVideo(body, contentType) {
  return parseExerciseMultipart(body, contentType, { defaultExerciseId: "push_up" }).video;
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

export function validateVerdict(value, definition = getExerciseDefinition("push_up")) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid verdict object");
  const keys = Object.keys(value).sort();
  const expectedKeys = ["confidence", "reason", definition.resultField].sort();
  if (keys.join(",") !== expectedKeys.join(",")) throw new Error("Unexpected verdict fields");
  if (!Number.isInteger(value[definition.resultField]) || value[definition.resultField] < 0 || value[definition.resultField] > 1000) {
    throw new Error(`Invalid ${definition.resultField}`);
  }
  if (typeof value.confidence !== "number" || value.confidence < 0 || value.confidence > 1) {
    throw new Error("Invalid confidence");
  }
  if (typeof value.reason !== "string" || value.reason.length > 2000) throw new Error("Invalid reason");
  return value;
}

export function validateClassifierVerdict(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid verdict object");
  const keys = Object.keys(value).sort();
  if (keys.join(",") !== ["exercise_id", "metric", "reason", "value"].join(",")) {
    throw new Error("Unexpected verdict fields");
  }
  if (!CLASSIFIER_EXERCISE_IDS.includes(value.exercise_id)) throw new Error("Invalid exercise_id");
  const expectedMetric = expectedMetricForExercise(value.exercise_id);
  if (value.metric !== expectedMetric) throw new Error("Verification metric does not match exercise");
  if (!Number.isInteger(value.value) || value.value < 0 || value.value > 1000) {
    throw new Error("Invalid value");
  }
  if (value.exercise_id === "unsupported" && value.value !== 0) throw new Error("Unsupported clips must return value 0");
  if (typeof value.reason !== "string" || value.reason.length > 2000) throw new Error("Invalid reason");
  return value;
}

export function normalizeClassifierVerdict(value) {
  const verdict = validateClassifierVerdict(value);
  if (verdict.exercise_id !== "plank") return verdict;
  return {
    ...verdict,
    value: Math.round(verdict.value / DENSIFY_FACTOR),
  };
}

const classifierSchema = Object.freeze({
  type: "object",
  properties: {
    exercise_id: { type: "string", enum: [...CLASSIFIER_EXERCISE_IDS] },
    metric: { type: "string", enum: ["repetitions", "hold_seconds", "none"] },
    value: { type: "integer", minimum: 0 },
    reason: { type: "string" },
  },
  required: ["exercise_id", "metric", "value", "reason"],
  additionalProperties: false,
});

async function callOpenRouter(videoPath, apiKey, request) {
  const video = await fs.readFile(videoPath);
  const payload = {
    model: MODEL,
    messages: [
      { role: "system", content: request.systemPrompt },
      {
        role: "user",
        content: [
          { type: "video_url", video_url: { url: `data:video/mp4;base64,${video.toString("base64")}` } },
          { type: "text", text: request.userPrompt },
        ],
      },
    ],
    reasoning: { effort: "low", exclude: true },
    max_tokens: 4096,
    response_format: {
      type: "json_schema",
      json_schema: {
        name: request.schemaName,
        strict: true,
        schema: request.schema,
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
        "X-Title": "browser-restrictor-exercise-verifier",
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
    const verdict = request.validate(JSON.parse(envelope.choices[0].message.content));
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

function legacyPushupModelRequest(definition) {
  return {
    systemPrompt: definition.systemPrompt,
    userPrompt: definition.userPrompt,
    schemaName: definition.schemaName,
    schema: {
      type: "object",
      properties: {
        confidence: { type: "number", minimum: 0, maximum: 1 },
        [definition.resultField]: { type: "integer", minimum: 0 },
        reason: { type: "string" },
      },
      required: ["confidence", definition.resultField, "reason"],
      additionalProperties: false,
    },
    validate: (value) => validateVerdict(value, definition),
  };
}

const classifierModelRequest = Object.freeze({
  systemPrompt: classifierPrompt,
  userPrompt: classifierUserPrompt,
  schemaName: "exercise_classification",
  schema: classifierSchema,
  validate: normalizeClassifierVerdict,
});

async function cleanupStaleDirectories() {
  const parent = os.tmpdir();
  const now = Date.now();
  for (const entry of await fs.readdir(parent, { withFileTypes: true })) {
    if (
      !entry.isDirectory() ||
      (!entry.name.startsWith(TEMP_PREFIX) && !entry.name.startsWith(LEGACY_TEMP_PREFIX))
    ) continue;
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
      return json(res, 200, {
        status: "ok",
        model: MODEL,
        exercises: publicExerciseCatalog(),
      });
    }
    const legacyPushupRequest = req.method === "POST" && req.url === "/v1/pushup-verify";
    const exerciseRequest = req.method === "POST" && req.url === "/v1/exercise-verify";
    if (!legacyPushupRequest && !exerciseRequest) {
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
      const upload = parseExerciseMultipart(body, req.headers["content-type"], {
        defaultExerciseId: legacyPushupRequest ? "push_up" : null,
        requireExerciseId: legacyPushupRequest,
      });
      if (legacyPushupRequest && upload.exerciseId !== "push_up") {
        throw new HttpError(400, "The legacy endpoint accepts only push_up");
      }
      tempDirectory = await fs.mkdtemp(path.join(os.tmpdir(), TEMP_PREFIX));
      const inputPath = path.join(tempDirectory, "input.mp4");
      const outputPath = path.join(tempDirectory, "prepared.mp4");
      await fs.writeFile(inputPath, upload.video, { mode: 0o600 });
      const metadata = await inspectVideo(inputPath);
      await prepareVideo(inputPath, outputPath);
      const modelStartedAt = performance.now();
      if (legacyPushupRequest) {
        const definition = getExerciseDefinition("push_up");
        if (!exerciseIsReady(definition)) {
          throw new HttpError(409, `Exercise verification is not ready: ${definition.pending.join(", ")}`);
        }
        const { verdict, diagnostics } = await callOpenRouter(
          outputPath,
          apiKey,
          legacyPushupModelRequest(definition),
        );
        const value = verdict[definition.resultField];
        console.log(JSON.stringify({
          request_id: requestId,
          status: "ok",
          exercise_id: definition.id,
          metric: definition.metric,
          value,
          source_seconds: Number(metadata.duration.toFixed(3)),
          processed_seconds: Number(metadata.duration.toFixed(3)),
          model_ms: Math.round(performance.now() - modelStartedAt),
          total_ms: Math.round(performance.now() - startedAt),
          ...diagnostics,
        }));
        return json(res, 200, {
          is_success: value >= definition.unitsPerSet,
          confidence: verdict.confidence,
          reason: verdict.reason,
          repetition_count: value,
        });
      }

      const { verdict, diagnostics } = await callOpenRouter(outputPath, apiKey, classifierModelRequest);
      console.log(JSON.stringify({
        request_id: requestId,
        status: "ok",
        exercise_id: verdict.exercise_id,
        metric: verdict.metric,
        value: verdict.value,
        source_seconds: Number(metadata.duration.toFixed(3)),
        processed_seconds: Number(metadata.duration.toFixed(3)),
        model_ms: Math.round(performance.now() - modelStartedAt),
        total_ms: Math.round(performance.now() - startedAt),
        ...diagnostics,
      }));
      return json(res, 200, {
        exercise_id: verdict.exercise_id,
        metric: verdict.metric,
        value: verdict.value,
        reason: verdict.reason,
      });
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
  const { host, port } = listenAddress();
  const server = createServer({ apiKey, sharedSecret });
  server.listen(port, host, () => console.log(JSON.stringify({ status: "listening", host, port, model: MODEL })));
}

if (path.resolve(process.argv[1] ?? "") === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}
