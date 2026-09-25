import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { runProcess, verifyVideo } from '../evidence.mjs';

let directory, video;
before(async () => {
  directory = await fs.mkdtemp(path.join(os.tmpdir(), 'friction-video-test-'));
  const file = path.join(directory, 'source.mp4');
  await runProcess('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', 'color=c=blue:s=160x120:r=4', '-t', '2',
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', file]);
  video = await fs.readFile(file);
});
after(async () => { if (directory) await fs.rm(directory, { recursive: true, force: true }); });

const task = { name: 'Custom squats', metric: 'repetitions', instructions: 'Count complete squat cycles.' };
function provider(verdict, inspect = () => {}, finish = 'stop') {
  return async (_url, options) => {
    await inspect(JSON.parse(options.body));
    return { ok: true, json: async () => ({ choices: [{ finish_reason: finish, message: { content: JSON.stringify(verdict) } }],
      provider: 'test', usage: { cost: 0.01 }, id: 'test-response' }) };
  };
}

test('selected custom task uses the single measurement schema after real MP4 preprocessing', async () => {
  let started = 0, usage;
  const result = await verifyVideo({ task }, video, { key: 'test-key',
    onProviderStart: () => { started++; }, onUsage: value => { usage = value; },
    fetcher: provider({ measured_units: 3, confidence: 0.9, reason: 'Three squats' }, async body => {
      const schema = body.response_format.json_schema;
      assert.equal(schema.strict, true);
      assert.equal(schema.schema.additionalProperties, false);
      assert.deepEqual(schema.schema.required, ['measured_units', 'confidence', 'reason']);
      assert(body.messages[0].content.includes(JSON.stringify(task)));
      assert(!body.messages[0].content.includes('repetition_count'));
      const uri = body.messages[1].content.find(part => part.type === 'video_url').video_url.url;
      assert(uri.startsWith('data:video/mp4;base64,'));
      const sampled = path.join(directory, 'sampled.mp4');
      await fs.writeFile(sampled, Buffer.from(uri.split(',')[1], 'base64'));
      const info = JSON.parse(await runProcess('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'json', sampled]));
      assert.equal(Number(info.format.duration), 8);
    }),
  });
  assert.equal(result.measuredUnits, 3);
  assert.equal(result.confidence, 0.9);
  assert.equal(result.diagnostics.sourceSeconds, 2);
  assert.equal(started, 1);
  assert.equal(usage.cost, 0.01);
});

test('duration tasks convert playback seconds once and retain tenths of a second', async () => {
  const result = await verifyVideo({ task: { ...task, name: 'Hold', metric: 'seconds' } }, video, {
    key: 'test-key', fetcher: provider({ measured_units: 5.2, confidence: 0.8, reason: 'Visible hold' }),
  });
  assert.equal(result.measuredUnits, 1.3);
});

test('zero activity is a valid measurement', async () => {
  const result = await verifyVideo({ task }, video, { key: 'test-key',
    fetcher: provider({ measured_units: 0, confidence: 1, reason: 'No activity' }),
  });
  assert.equal(result.measuredUnits, 0);
});

for (const [name, verdict] of [
  ['legacy count format', { repetition_count: 3, confidence: 0.9, reason: 'Old format' }],
  ['classifier format', { exercise_id: 'push_up', metric: 'repetitions', value: 3, reason: 'Old format' }],
  ['extra fields', { measured_units: 3, confidence: 0.9, reason: 'Cycles', is_success: true }],
  ['null result', null],
  ['fractional repetitions', { measured_units: 1.5, confidence: 0.9, reason: 'Invalid' }],
  ['impossible count', { measured_units: 50, confidence: 0.9, reason: 'Invalid' }],
  ['invalid confidence', { measured_units: 3, confidence: 2, reason: 'Invalid' }],
]) test(`single evaluator rejects ${name}`, async () => {
  await assert.rejects(verifyVideo({ task }, video, { key: 'test-key', fetcher: provider(verdict) }),
    error => error.status === 502);
});

test('incomplete model output cannot become a receipt', async () => {
  await assert.rejects(verifyVideo({ task }, video, { key: 'test-key',
    fetcher: provider({ measured_units: 3, confidence: 0.9, reason: 'Partial' }, () => {}, 'length'),
  }), /incomplete result/);
});

for (const [name, verdict, message] of [
  ['an array result', [], /returned an invalid result/],
  ['negative units', { measured_units: -1, confidence: 0.9, reason: 'Invalid' }, /measurement was invalid/],
  ['text units', { measured_units: '3', confidence: 0.9, reason: 'Invalid' }, /measurement was invalid/],
  ['negative confidence', { measured_units: 3, confidence: -0.1, reason: 'Invalid' }, /measurement was invalid/],
  ['a numeric reason', { measured_units: 3, confidence: 0.9, reason: 3 }, /measurement was invalid/],
]) test(`video verdict with ${name} is rejected with its specific reason`, async () => {
  await assert.rejects(verifyVideo({ task }, video, { key: 'test-key', fetcher: provider(verdict) }),
    error => error.status === 502 && message.test(error.message));
});
