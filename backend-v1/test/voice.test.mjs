import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {Store} from '../store.mjs';
import {Agent} from '../agent.mjs';
import {Voice} from '../voice.mjs';
import {seed} from '../domain.mjs';

const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(check){for(let i=0;i<200;i++){if(check())return;await pause(5);}assert.fail('Timed out waiting for local voice state');}
function model(output){return{ok:true,status:200,json:async()=>({id:'mock',status:'completed',service_tier:'fast',usage:{input_tokens:100,output_tokens:20},output})};}
function changed(){const config=seed();config.settings.waitSeconds=5;return model([{type:'function_call',name:'propose_changes',call_id:'set_wait',arguments:JSON.stringify({changes:[{action:'set_setting',field:'waitSeconds',value:5}],expectedRevision:0,title:'Wait five seconds'})}]);}
async function fixture(t,fetcher){
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'friction-voice-test-'));
 const store=await new Store(dir).init(),{installationId:install}=await store.register(),agent=new Agent(store,{key:'mock',fetcher}),sent=[];
 const voice=new Voice(store,agent,{cancellationDelayMs:40,delegationDelayMs:1,progressDelayMs:100});
 const s={installationId:install,sessionId:'test',socket:{readyState:1,send:raw=>sent.push(JSON.parse(raw))},reservation:null,startedAt:Date.now(),seconds:0,closed:false,pending:'',pendingVersion:0,inputEvents:new Set(),queue:[],pumping:false,handled:new Set(),epoch:0};
 voice.sessions.set(s.sessionId,s);store.getRuntime(install).voice={sessionId:s.sessionId};t.after(async()=>{await voice.finished(s);await fs.rm(dir,{recursive:true,force:true});});
 const event=e=>voice.event(s,JSON.stringify(e));
 let serial=0;const input=(delta,start_ms=0,end_ms=start_ms+200)=>event({type:'session.input_transcript.delta',event_id:'input_'+(++serial),delta,start_ms,end_ms});
 const delegate=(name,offset_ms)=>event({type:'session.delegation.created',delegation:{id:name},offset_ms});
 return{store,install,agent,voice,s,sent,input,delegate,event};
}

test('actual phone transcript sequence cancels busy Astra without a second delegation or late review',async t=>{
 let release;const f=await fixture(t,()=>new Promise(resolve=>release=resolve));
 // Same fragments, timeline and sole delegation as the failing physical test.
 for(const [delta,start,end] of [[' Please set',1600,1800],[' my',1800,2000],[' waiting',2200,2400],[' time to',2600,2800],[' five',3000,3200],[' seconds',3600,3800]])await f.input(delta,start,end);
 await f.delegate('original_request',3600);await until(()=>release);
 await f.input(' Cancel',6000,6200);await pause(5);await f.input(' that',6400,6600);
 await until(()=>f.store.get(f.install).messages.some(m=>m.role==='assistant'&&m.text.startsWith('Cancelled.')));
 assert.equal(f.store.getRuntime(f.install).turn.cancelled,true);
 assert(f.store.get(f.install).messages.some(m=>m.source==='voice'&&m.role==='user'&&m.text==='Cancel that'));
 assert.equal(f.store.snapshot(f.install).voiceActive,true);
 await pause(110); // No stale four-second-equivalent progress after cancellation.
 release(changed());await until(()=>!f.s.pumping);
 assert.equal(f.store.get(f.install).revision,0);
 assert.equal(f.store.get(f.install).proposals.length,0);
 assert.equal(f.store.usage().calls,1);
 assert(f.sent.some(e=>e.content.includes('pending request is cancelled')));
 assert(!f.sent.some(e=>/Still preparing|Review the change/.test(e.content)));
});

test('negative, quoted, hypothetical and continued transcript commands do not cancel a review',async t=>{
 const f=await fixture(t,async()=>model([])),config=seed();config.settings.waitSeconds=5;
 const p=await f.store.stage(f.install,{config,expectedRevision:0});
 for(const fragments of [[" Don't",' cancel that.'],['What happens if I say ','cancel that?'],['She said: ','cancel that.'],['"Cancel',' that"'],['Cancel',' that',' would be a mistake.'],['Cancel',' the button label?']]){
  f.s.pending='';f.s.pendingVersion++;
  for(const fragment of fragments){await f.input(fragment);await pause(5);}
  await pause(50);
  assert.equal(f.store.get(f.install).proposals.find(x=>x.id===p.id).status,'pending',fragments.join(''));
 }
 assert.equal(f.store.get(f.install).messages.length,0);
 assert.equal(f.store.get(f.install).revision,0);
});

test('an early cancel delegation cannot bypass transcript stability or authorize a later continuation',async t=>{
 const f=await fixture(t,async()=>model([])),config=seed();config.settings.waitSeconds=5;
 await f.store.stage(f.install,{config,expectedRevision:0});
 await f.input('Cancel',1000,1200);await f.delegate('early',1000);await pause(5);await f.input(' that would be a mistake.',1200,1600);await pause(50);
 assert.equal(f.store.get(f.install).proposals[0].status,'pending');assert.equal(f.store.get(f.install).messages.length,0);
});

test('duplicate transcript and delayed delegation cannot undo older work or consume a new request',async t=>{
 const f=await fixture(t,async()=>model([{type:'message',content:[{type:'output_text',text:'Ready for the next request.'}]}]));
 const config=seed();config.settings.waitSeconds=9;const old=await f.store.stage(f.install,{config,expectedRevision:0});await f.store.confirm(f.install,old.id,{version:old.version});
 config.settings.waitSeconds=7;await f.store.stage(f.install,{config,expectedRevision:1});
 const cancel={type:'session.input_transcript.delta',event_id:'same_cancel',delta:'Cancel that.',start_ms:6000,end_ms:6600};
 await f.event(cancel);await until(()=>f.store.get(f.install).messages.some(m=>m.text.startsWith('Cancelled.')));
 await f.event(cancel);await f.input('Explain the current wait.',8000,9000);await f.delegate('late_cancel',6400);
 assert.equal(f.s.pending,'Explain the current wait.');
 await f.delegate('new_request',8800);await until(()=>!f.s.pumping);await pause(50);
 assert.equal(f.store.get(f.install).revision,1);assert.equal(f.store.get(f.install).config.settings.waitSeconds,9);
 assert.equal(f.store.get(f.install).messages.filter(m=>m.role==='user'&&m.text==='Cancel that.').length,1);
 assert.equal(f.store.get(f.install).messages.at(-1).text,'Ready for the next request.');
});

test('ending the voice session clears a pending cancellation candidate',async t=>{
 const f=await fixture(t,async()=>model([])),config=seed();config.settings.waitSeconds=5;await f.store.stage(f.install,{config,expectedRevision:0});
 await f.input('Cancel that.');await f.voice.finished(f.s);await pause(50);
 assert.equal(f.store.get(f.install).proposals[0].status,'pending');assert.equal(f.store.get(f.install).messages.length,0);
});

test('duration-limit closure records a session-specific reason before transport closes',async t=>{
 const f=await fixture(t,async()=>model([]));
 f.voice.hangup=async()=>{};
 f.s.socket.close=()=>{};f.s.socket.terminate=()=>{};
 await f.voice.close(f.install,'test','duration_limit');
 assert.deepEqual(f.store.snapshot(f.install).lastVoiceEnd,{sessionId:'test',reason:'duration_limit',maxSeconds:300});
 await f.voice.close(f.install,'test');
 assert.equal(f.store.snapshot(f.install).lastVoiceEnd.reason,'duration_limit');
});
test('manual hangup is not reported as a duration limit',async t=>{
 const f=await fixture(t,async()=>model([]));
 f.voice.hangup=async()=>{};f.s.socket.close=()=>{};f.s.socket.terminate=()=>{};
 await f.voice.close(f.install,'test');
 assert.equal(f.store.snapshot(f.install).lastVoiceEnd.reason,'user');
});

test('voice creation reserves once, authenticates the transport and settles after closure',async t=>{
 const f=await fixture(t,async()=>model([]));await f.voice.finished(f.s);
 const {EventEmitter}=await import('node:events');let socket,request,settled;
 class Socket extends EventEmitter{readyState=1;send(){}close(){this.emit('close');}terminate(){this.emit('close');}}
 const voice=new Voice(f.store,f.agent,{key:'fixture-key',maxSeconds:30,fetcher:async(url,options)=>{
  if(url.endsWith('/hangup'))return{ok:true};request={url,options};return{ok:true,json:async()=>({session:{id:'fixture/session'},transport:{sdp:'v=0 answer'}})};
 },socketFactory:(url,options)=>{assert.match(url,/fixture%2Fsession\/attach$/);assert.equal(options.headers.Authorization,'Bearer fixture-key');socket=new Socket();setImmediate(()=>socket.emit('open'));return socket;}});
 t.after(async()=>{for(const s of voice.sessions.values())await voice.finished(s);});
 const created=await voice.create(f.install,'v=0 offer');assert.deepEqual(created,{sessionId:'fixture/session',sdp:'v=0 answer'});
 assert.equal(request.options.headers.Authorization,'Bearer fixture-key');const body=JSON.parse(request.options.body);assert.equal(body.transport.sdp,'v=0 offer');assert.equal(body.session.audio.output.voice,'ripple');
 assert.equal(f.store.getRuntime(f.install).voice.sessionId,'fixture/session');
 await assert.rejects(voice.create(f.install,'v=0 duplicate'),/already active/);
 const session=voice.sessions.get(created.sessionId);session.finalUsage=true;session.seconds=12;
 await voice.finished(session);await voice.finished(session);assert.equal(voice.sessions.size,0);assert.equal(f.store.getRuntime(f.install).voice,null);
});

test('voice creation failures clear reservations and do not retain an active call',async t=>{
 const f=await fixture(t,async()=>model([]));await f.voice.finished(f.s);
 const unconfigured=new Voice(f.store,f.agent,{key:''});await assert.rejects(unconfigured.create(f.install,'v=0'),/not configured/);
 const voice=new Voice(f.store,f.agent,{key:'fixture',fetcher:async()=>({ok:false,json:async()=>({})})});
 await assert.rejects(voice.create(f.install,'invalid'),/offer/);
 await assert.rejects(voice.create(f.install,'v=0'),/could not be created/);assert.equal(f.store.getRuntime(f.install).voice,null);assert.equal(voice.sessions.size,0);
 const {EventEmitter}=await import('node:events');let terminated=false,hangups=0;
 const failed=new Voice(f.store,f.agent,{key:'fixture',fetcher:async url=>{if(url.endsWith('/hangup')){hangups++;return{ok:true};}return{ok:true,json:async()=>({session:{id:'failed'},transport:{sdp:'v=0'}})};},socketFactory:()=>{const socket=new EventEmitter();socket.terminate=()=>{terminated=true;};setImmediate(()=>socket.emit('error',Error('fixture connection failure')));return socket;}});
 await assert.rejects(failed.create(f.install,'v=0'),/sideband connection failed/);assert.equal(terminated,true);assert.equal(hangups,1);assert.equal(failed.sessions.size,0);assert.equal(f.store.getRuntime(f.install).voice,null);
});

test('voice context explains saved versus applied state, selected cards and receipt eligibility',async t=>{
 const f=await fixture(t,async()=>model([]));
 const config=seed();config.settings.waitSeconds=3;const p=await f.store.stage(f.install,{config,expectedRevision:0});
 assert(f.sent.some(e=>e.type==='session.thinking.append'&&e.content.includes('setting:waitSeconds')));
 const saved=await f.store.confirm(f.install,p.id,{version:p.version});assert(f.sent.some(e=>e.content.includes('not acknowledged')));
 f.voice.update({installationId:f.install,kind:'applied',detail:{applicationStatus:'awaiting_device'}});assert(f.sent.some(e=>e.content.includes('still applying')));
 await f.store.ack(f.install,1);assert(f.sent.some(e=>e.content.includes('revision 1 is active')));
 const state=f.store.get(f.install);state.selectedCardId=p.cards[0].id;
 f.voice.update({installationId:f.install,kind:'context'});assert(f.sent.some(e=>e.content.includes('looking at this card')));
 const count=f.sent.length;f.voice.update({installationId:f.install,kind:'context'});assert.equal(f.sent.length,count);
 state.selectedCardId=null;f.voice.update({installationId:f.install,kind:'context'});assert.match(f.sent.at(-1).content,/Saved revision 1/);
 for(const detail of [{task:{name:'Walk'},measuredUnits:3,metric:'meters',rewardEligible:true,minutesRewardEligible:false},{taskId:'walk',measuredUnits:0,metric:'meters',rewardEligible:false}])f.voice.update({installationId:f.install,kind:'receipt',detail});
 assert.match(f.sent.at(-2).content,/Browsing minutes eligible: false/);assert.match(f.sent.at(-1).content,/walk/);
 f.voice.update({installationId:'unrelated',kind:'saved',detail:saved});
 await f.store.cancel(f.install,{proposalId:p.id});assert(f.sent.some(e=>e.content.includes('Undo is saved')));
});

test('voice event decoding handles usage, closure and provider error shapes without replaying closed sessions',async t=>{
 const f=await fixture(t,async()=>model([])),errors=[];
 f.store.on('change',event=>{if(event.kind==='voice_error')errors.push(event.detail.error);});
 await f.voice.event(f.s,'not-json');
 for(const [value,expected]of [[{error:{message:'nested'}},'nested'],[{message:'outer'},'outer'],[{},'Voice error.']]){
  await f.event({type:'error',...value});assert.equal(errors.at(-1),expected);
 }
 await f.event({type:'session.usage.updated',usage:{seconds:12}});assert.equal(f.s.seconds,12);
 await f.event({type:'session.usage.updated'});assert.equal(f.s.seconds,12);
 let closes=0;f.s.socket.close=()=>closes++;
 await f.event({type:'session.closed',usage:{seconds:15}});assert.equal(f.s.seconds,15);assert.equal(f.s.finalUsage,true);assert.equal(closes,1);
 await f.event({type:'session.closed'});assert.equal(f.s.seconds,15);assert.equal(f.s.finalUsage,false);
 const count=errors.length;f.s.closed=true;await f.event({type:'error',message:'too late'});assert.equal(errors.length,count);f.s.closed=false;
});

test('voice progress is sent while a delegated request is pending and is cleared after its answer',async t=>{
 let release;const f=await fixture(t,()=>new Promise(resolve=>release=resolve));
 await f.input('Explain my current setup');await f.delegate('progress',1000);
 await until(()=>f.sent.some(event=>event.content.includes('Still preparing')));
 assert.equal(f.s.pumping,true);assert.notEqual(f.s.progressTimer,null);
 release(model([{type:'message',content:[{type:'output_text',text:'Your daily allowance starts at reset.'}]}]));
 await until(()=>!f.s.pumping);
 assert.equal(f.s.progressTimer,null);assert(f.sent.some(event=>event.content==='Your daily allowance starts at reset.'));
 const count=f.sent.length;await pause(120);assert.equal(f.sent.length,count);
});

test('empty, invalid and closed voice inputs cannot cancel or enqueue work; absent transcript asks for repetition',async t=>{
 const f=await fixture(t,async()=>model([{type:'message',content:[{type:'output_text',text:'Explained.'}]}]));
 for(const delta of ['',null,5])await f.event({type:'session.input_transcript.delta',delta});
 assert.equal(f.s.pending,'');assert.equal(f.s.queue.length,0);
 await f.delegate('no-transcript',0);assert.match(f.sent.at(-1).content,/Please repeat/);
 const count=f.sent.length;await f.voice.cancelTranscript(f.s,'What does cancel mean?');assert.equal(f.sent.length,count);
 f.s.closed=true;await f.voice.cancelTranscript(f.s,'cancel');assert.equal(f.sent.length,count);f.s.closed=false;
 await f.voice.cancelTranscript(f.s,'cancel');assert.equal(f.s.lastCancellation.throughMs,0);assert.equal(f.s.cancelling,null);
 const cancelledCount=f.sent.length;await f.delegate('after-cancel',Infinity);assert.equal(f.sent.length,cancelledCount);
 await f.event({type:'session.input_transcript.delta',delta:'Explain my rules',end_ms:0});
 // Buffered legacy session state can lack the fragment-version field.
 delete f.s.pendingVersion;await f.delegate('new-request',Infinity);assert.equal(f.s.pendingVersion,1);await until(()=>!f.s.pumping);
 assert.equal(f.s.pending,'');assert(f.sent.some(e=>e.content==='Explained.'));
});
