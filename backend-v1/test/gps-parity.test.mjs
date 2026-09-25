import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import {verifyGps} from '../evidence.mjs';

// The Android live counter (GpsDistanceTracker.kt) is tested against the same fixture.
const cases=JSON.parse(fs.readFileSync(new URL('./fixtures/gps-parity.json',import.meta.url)));
for(const c of cases)test(`GPS parity fixture: ${c.name}`,()=>{
 assert.equal(verifyGps({startedAt:c.startedAt,task:{gpsMode:c.mode}},{points:c.points,endedAt:c.endedAt}).measuredUnits,c.expectedMeters);
});
