import test from 'node:test';import assert from 'node:assert/strict';
import {seed} from './domain.mjs';import {makeFunctionRunner} from './functions.mjs';import {isCancellationRequest} from './cancellation.mjs';
function fixture(hook=async()=>{}){
 const s={config:seed(),revision:0,drafts:[],changes:[]};let disk=structuredClone(s);
 const run=makeFunctionRunner({state:()=>s,sections:[],save:async()=>{disk=structuredClone(s);await hook(s);},readPersisted:async()=>structuredClone(disk),emit:()=>{}});
 const ctx=(id='p',msg='')=>({turnId:id,conversationId:'c',userMessage:msg,readRevision:null,pending:[],verified:[]});
 async function propose(setting='waitSeconds',value='5',id='p'){const c=ctx(id);await run('read_setup',{},c);return run('propose_setting_change',{expectedRevision:s.revision,setting,value},c);}
 async function approve(draftId,c=ctx('a','yes')){await run('read_setup',{},c);return run('confirm_change',{draftId,expectedRevision:s.revision,approvalQuote:'yes'},c);}
 const cancel=(draftId=null,c=ctx('x','cancel that'))=>run('cancel_change',{draftId,changeId:null,requestQuote:c.userMessage},c);
 return {s,run,ctx,propose,approve,cancel};
}
test('recognizes direct cancellation without negations or ambiguous instructions',()=>{for(const s of ['Cancel that!','Please undo the last change','Wait, cancel that.','never mind'])assert.ok(isCancellationRequest(s));for(const s of ["Don't cancel that",'how do I cancel?','cancel the walk task','yes but cancel later'])assert.equal(isCancellationRequest(s),false);});
test('cancel pending proposal persists discard and prevents later approval',async()=>{const f=fixture(),d=await f.propose();const c=await f.cancel(d.draftId);assert.equal(c.verified,true);assert.equal(f.s.config.settings.waitSeconds,12);assert.equal(f.s.drafts[0].status,'cancelled');await assert.rejects(f.approve(d.draftId));});
test('undo saved change restores previous value and verifies its own receipt; retries are idempotent',async()=>{const f=fixture(),d=await f.propose();await f.approve(d.draftId);const c=f.ctx('u','undo that'),r=await f.cancel(d.draftId,c);assert.equal(f.s.config.settings.waitSeconds,12);assert.equal((await f.run('verify_change',{changeId:r.changeId},c)).verified,true);const again=await f.cancel(d.draftId);assert.equal(again.changeId,r.changeId);assert.equal(f.s.changes.length,2);assert.equal(f.s.drafts[0].status,'undone');});
test('undo preserves unrelated changes and rejects a newer edit of the same field',async()=>{const f=fixture(),d=await f.propose();await f.approve(d.draftId);let other=await f.propose('minutesPerCredit','7','p2');await f.approve(other.draftId,f.ctx('a2','yes'));await f.cancel(d.draftId);assert.equal(f.s.config.settings.waitSeconds,12);assert.equal(f.s.config.settings.minutesPerCredit,7);
 const g=fixture(),x=await g.propose();await g.approve(x.draftId);const y=await g.propose('waitSeconds','8','p2');await g.approve(y.draftId,g.ctx('a2','yes'));await assert.rejects(g.cancel(x.draftId),/changed again/);assert.equal(g.s.config.settings.waitSeconds,8);
});
test('cancellation before queued save blocks the write',async()=>{const f=fixture(),d=await f.propose(),c=f.ctx('a','yes');await f.run('read_setup',{},c);c.cancelled=true;await assert.rejects(f.run('confirm_change',{expectedRevision:0,draftId:d.draftId,approvalQuote:'yes'},c),/cancelled/);await f.cancel(d.draftId);assert.equal(f.s.changes.length,0);assert.equal(f.s.config.settings.waitSeconds,12);});
test('cancellation arriving during disk save serializes undo after save and verifies restoration',async()=>{
 let release,entered;const gate=new Promise(r=>release=r),saving=new Promise(r=>entered=r);let pause=true;
 const f=fixture(async s=>{if(pause&&s.changes.length===1){pause=false;entered();await gate;}}),d=await f.propose(),a=f.ctx('a','yes');
 const savingChange=f.approve(d.draftId,a);await saving;a.cancelled=true;
 const cancellation=f.cancel(d.draftId,f.ctx('u','cancel that'));release();await savingChange;const r=await cancellation;
 assert.equal(f.s.config.settings.waitSeconds,12);assert.equal(f.s.revision,2);assert.equal((await f.run('verify_change',{changeId:r.changeId},f.ctx('v'))).verified,true);
});
test('cancelling a new in-flight request does not undo an earlier saved change',async()=>{const f=fixture(),d=await f.propose();await f.approve(d.draftId);const ctx=f.ctx('cancel','cancel that');ctx.targetTurnId='new-read-request';await f.cancel(null,ctx);assert.equal(f.s.config.settings.waitSeconds,5);});
test('cancelled generation cannot create a late proposal',async()=>{const f=fixture(),c=f.ctx();c.cancelled=true;await assert.rejects(f.run('propose_setting_change',{expectedRevision:0,setting:'waitSeconds',value:'8'},c),/cancelled/);assert.equal(f.s.drafts.length,0);});
test('undo added task removes only that unchanged task',async()=>{const f=fixture(),c=f.ctx();await f.run('read_setup',{},c);c.docsRead=true;const d=await f.run('draft_task',{name:'Brush',verifier:'video',metric:'seconds',unitsPerCredit:20,correctionMinimum:1,instructions:'Visible brushing.'},c);await f.approve(d.draftId);assert.equal(f.s.config.tasks.length,5);await f.cancel(d.draftId);assert.equal(f.s.config.tasks.length,4);});
test('cancel tool rejects fabricated authorization and cross-conversation targets',async()=>{const f=fixture(),d=await f.propose();await assert.rejects(f.run('cancel_change',{draftId:d.draftId,changeId:null,requestQuote:'cancel that'},f.ctx('x','What is this?')),/Explicit/);const c=f.ctx('x','cancel that');c.conversationId='other';await assert.rejects(f.cancel(d.draftId,c),/not found/);});
test('model can quote a natural undo request in full',async()=>{const f=fixture(),d=await f.propose();await f.approve(d.draftId);const c=f.ctx('u','Please undo my most recent setting adjustment.');const r=await f.cancel(d.draftId,c);assert.equal(f.s.config.settings.waitSeconds,12);assert.equal((await f.run('verify_change',{changeId:r.changeId},c)).verified,true);});
