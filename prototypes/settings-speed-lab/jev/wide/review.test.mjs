import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {Store} from '../../../../backend-v1/store.mjs';
import {Agent} from '../../../../backend-v1/agent.mjs';
import {captureBase} from '../../../../backend-v1/edits.mjs';
import {applyCards,seed} from '../../../../backend-v1/domain.mjs';
async function setup(t){const dir=await fs.mkdtemp(path.join(os.tmpdir(),'jev-review-'));t.after(()=>fs.rm(dir,{recursive:true,force:true}));const store=await new Store(dir).init();const {installationId:id}=await store.register();return{store,id,dir};}
const wait=value=>({action:'set_setting',field:'waitSeconds',value});
test('pending edit amendment preserves other cards and stale results cannot overwrite review',async t=>{const{store,id}=await setup(t);await store.stage(id,{changes:[wait(8),{action:'set_task_field',taskId:'plank',field:'paused',value:true}],expectedRevision:0});const old=captureBase(store,id);const p=await store.stage(id,{changes:[wait(21)],expectedRevision:0});const after=applyCards(store.get(id).config,p.cards);assert.equal(after.settings.waitSeconds,21);assert.equal(after.tasks[1].paused,true);await assert.rejects(store.stage(id,{changes:[wait(4)],expectedRevision:0},{editBase:old}),/review changed/);assert.deepEqual(store.get(id).config,seed());});
test('apply, durable reload, and undo use no model calls',async t=>{const{store,id,dir}=await setup(t);await store.stage(id,{changes:[wait(22)],expectedRevision:0});const agent=new Agent(store,{fetcher:()=>{throw Error('Unexpected model call');}});await agent.chat(id,'Apply these changes'.replace('these','the'));assert.equal((await new Store(dir).init()).get(id).config.settings.waitSeconds,22);await agent.chat(id,'Undo that');assert.deepEqual(store.get(id).config,seed());});
test('discarding review does not change saved setup',async t=>{const{store,id}=await setup(t);await store.stage(id,{changes:[wait(31)],expectedRevision:0});await new Agent(store,{fetcher:()=>{throw Error('Unexpected model call');}}).chat(id,'Cancel that');assert.equal(store.get(id).proposals.at(-1).status,'cancelled');assert.deepEqual(store.get(id).config,seed());});
test('isolated speculative branch cannot alter selected installation',async t=>{const a=await setup(t),b=await setup(t);const p=await b.store.stage(b.id,{changes:[wait(55)],expectedRevision:0});await b.store.confirm(b.id,p.id,{version:p.version});assert.deepEqual(a.store.get(a.id).config,seed());assert.equal(a.store.get(a.id).proposals.length,0);});
