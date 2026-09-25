import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {Store} from '../store.mjs';

async function fixture(t){
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'friction-recording-'));
 const now=Date.parse('2026-09-24T12:00:00Z'),store=await new Store(dir,{clock:()=>now}).init();
 t.after(()=>fs.rm(dir,{recursive:true,force:true}));
 const {installationId:install}=await store.register();
 return {store,install,now};
}

test('recording retries bind task, start, revision and preview identity without replacing pinned rules',async t=>{
 const {store,install,now}=await fixture(t);
 const body={attemptId:'attempt-fixture',taskId:'walk',startedAt:now-1000,configRevision:0};
 const original=await store.startAttempt(install,body);
 assert.deepEqual(await store.startAttempt(install,body),original);
 for(const patch of [{taskId:'sprint'},{startedAt:now},{configRevision:1},{proposalId:'other'},{proposalVersion:2}]){
  await assert.rejects(store.startAttempt(install,{...body,...patch}),/different recording/);
  assert.deepEqual(store.get(install).attempts[body.attemptId],original);
 }
 for(const startedAt of [0,NaN,now+60001])await assert.rejects(store.startAttempt(install,{...body,attemptId:'new-attempt',startedAt}),/start time/);
 await assert.rejects(store.startAttempt(install,{...body,attemptId:'short'}),/unique attempt/);
 await assert.rejects(store.startAttempt(install,{...body,attemptId:'new-attempt',configRevision:1}),/Unknown configuration/);
 await assert.rejects(store.startAttempt(install,{...body,attemptId:'new-attempt',taskId:'missing'}),/unavailable/);
 await assert.rejects(store.startAttempt(install,{...body,attemptId:'new-attempt',proposalId:'missing'}),/review changed/);
 assert.equal(Object.keys(store.get(install).attempts).length,1);
});

test('invalid measurements and recording endpoints cannot persist a partial reward receipt',async t=>{
 const {store,install,now}=await fixture(t);
 const attempt=await store.startAttempt(install,{attemptId:'endtime-fixture',taskId:'walk',startedAt:now-1000,configRevision:0});
 for(const endedAt of [NaN,now-1001,now+60001]){
  await assert.rejects(store.finishAttempt(install,attempt.attemptId,{endedAt,measuredUnits:100}),/recording end/);
  assert.equal(store.get(install).attempts[attempt.attemptId].receipt,undefined);
 }
 for(const measuredUnits of [NaN,-1,Infinity]){
  await assert.rejects(store.finishAttempt(install,attempt.attemptId,{endedAt:now,measuredUnits}),/Invalid verification/);
  assert.equal(store.get(install).attempts[attempt.attemptId].receipt,undefined);
 }
 const receipt=await store.finishAttempt(install,attempt.attemptId,{endedAt:now,measuredUnits:3.3,fallbackEligible:true});
 assert.equal(receipt.attemptId,attempt.attemptId);assert.equal(receipt.rewardEligible,true);
 assert.equal(receipt.fallbackEligible,true);assert.equal(receipt.maximumCorrectableUnits,null);
 assert.equal(receipt.reason,'');assert.equal(receipt.confidence,null);assert.equal(receipt.timeZone,'UTC');
 assert.deepEqual(await store.finishAttempt(install,attempt.attemptId,{endedAt:NaN,measuredUnits:1000}),receipt);
});

test('legacy second-based receipts without an explicit correction cap use recorded duration and available source duration',async t=>{
 const {store,install,now}=await fixture(t);
 const attempt=await store.startAttempt(install,{attemptId:'legacy-duration',taskId:'plank',startedAt:now-10000,configRevision:0});
 await store.finishAttempt(install,attempt.attemptId,{endedAt:now,measuredUnits:2});
 let receipt=store.get(install).attempts[attempt.attemptId].receipt;
 delete receipt.maximumCorrectableUnits;receipt.diagnostics={sourceSeconds:5};await store.persist();
 assert.equal((await store.correct(install,attempt.attemptId,5.1)).correctedUnits,5.1);
 await assert.rejects(store.correct(install,attempt.attemptId,5.2),/cannot exceed/);
 receipt=store.get(install).attempts[attempt.attemptId].receipt;receipt.diagnostics=null;await store.persist();
 assert.equal((await store.correct(install,attempt.attemptId,10.1)).correctedUnits,10.1);
 await assert.rejects(store.correct(install,attempt.attemptId,10.2),/cannot exceed/);
});
