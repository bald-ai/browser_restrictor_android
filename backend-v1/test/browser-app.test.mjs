import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {JSDOM} from 'jsdom';
import {Store} from '../store.mjs';

const settle = () => new Promise(resolve => setTimeout(resolve, 25));
let serial=0;
async function fixture(t, options={}) {
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'friction-dom-'));
 const store=await new Store(dir).init(), identity=await store.register(), install=identity.installationId;
 const dom=new JSDOM(await fs.readFile(new URL('../public/index.html',import.meta.url),'utf8'),{url:'https://fixture.invalid'});
 const {window}=dom;
 window.HTMLElement.prototype.scrollIntoView=function(){};
 window.HTMLElement.prototype.scrollTo=function(){};
 window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
 window.HTMLDialogElement.prototype.close=function(){this.open=false;};
 window.scrollTo=()=>{};
 if(options.native)window.FrictionNative={bootstrap:()=>JSON.stringify(identity),...options.native};
 else if(!options.register)window.localStorage.setItem('friction.v1.identity',JSON.stringify(identity));
 const originals=new Map(),requests=[],failRoutes=new Map();
 const globals={window,document:window.document,localStorage:window.localStorage,FormData:window.FormData,
  ResizeObserver:class{observe(){}disconnect(){}},getComputedStyle:window.getComputedStyle.bind(window),
  requestAnimationFrame:callback=>setTimeout(callback,0),cancelAnimationFrame:clearTimeout,
  fetch:async(url,options={})=>{
   const route=url.replace('/api/v1',''),body=options.body?JSON.parse(options.body):undefined;
   requests.push({route,body,headers:options.headers});
   if(route==='/installations')return{ok:true,json:async()=>identity};
   assert.equal(options.headers.Authorization,'Bearer '+identity.token);
   if(failRoutes.has(route))return{ok:false,json:async()=>failRoutes.get(route)};
   if(route==='/events')return {ok:true,body:new ReadableStream({start(c){options.signal.addEventListener('abort',()=>c.close());}})};
   try {
    let value;
    if(route==='/state')value=store.snapshot(install);
    else if(route==='/context')value=await store.context(install,body.selectedCardId);
    else if(route==='/proposals')value=await store.stage(install,body);
    else if(route==='/cancel')value=await store.cancel(install,body);
    else if(route.endsWith('/confirm'))value=await store.confirm(install,route.split('/')[2],body);
    else if(route.endsWith('/remove'))value=await store.remove(install,route.split('/')[2],body.version,body.cardId);
    else if(route==='/assistant-settings')value=await store.setAssistantSettings(install,body);
    else if(route==='/voice/session')value={sessionId:'fixture-voice',sdp:'v=0 answer'};
    else if(route==='/voice/close')value={ok:true};
    else if(route==='/chat'){await store.message(install,'user',body.message);value={ok:true};}
    else throw Error('Unexpected fixture route: '+route);
    return {ok:true,json:async()=>value};
   }catch(error){return{ok:false,json:async()=>({error:error.message})};}
  },...options.globals};
 for(const [key,value]of Object.entries(globals)){originals.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{value,writable:true,configurable:true});}
 t.after(async()=>{window.dispatchEvent(new window.Event('pagehide'));await settle();dom.window.close();for(const [key,descriptor]of originals)if(descriptor)Object.defineProperty(globalThis,key,descriptor);else delete globalThis[key];await fs.rm(dir,{recursive:true,force:true});});
 await import('../public/app.js?fixture='+serial++);await settle();
 const $=id=>window.document.getElementById(id);
 const refresh=async()=>{await window.FrictionNativeUpdate();await settle();};
 return {store,install,window,$,requests,failRoutes,refresh,stage:async change=>{const config=structuredClone(store.get(install).config);change(config);const proposal=await store.stage(install,{config,expectedRevision:store.get(install).revision});await refresh();return proposal;}};
}

test('browser settings are a proposal until confirmed and the latest save can be undone',async t=>{
 const f=await fixture(t),{$,window,store,install}=f;
 assert.equal($('connection').textContent,'Connected');
 $('setupTab').click();assert.equal($('setup').hidden,false);assert.equal($('composer').hidden,true);
 $('editSettings').click();assert.equal($('editor').open,true);
 $('editForm').elements.waitSeconds.value='7';
 $('editForm').dispatchEvent(new window.Event('submit',{cancelable:true}));await settle();
 assert.equal(store.get(install).config.settings.waitSeconds,12);
 assert.match($('cards').textContent,/7 sec/);assert.equal($('editor').open,false);
 $('applyChanges').click();await settle();
 assert.equal(store.get(install).config.settings.waitSeconds,7);
 assert.match($('reviewStatus').textContent,/WAITING FOR YOUR PHONE/);
 $('undoChanges').click();await settle();
 assert.equal(store.get(install).config.settings.waitSeconds,12);assert.equal($('reviewStatus').textContent,'UNDONE');
});

test('browser rejects stale settings edits without losing the editor or applying them',async t=>{
 const f=await fixture(t),{$,window,store,install}=f;
 $('editSettings').click();$('editForm').elements.waitSeconds.value='7';
 const p=await f.stage(c=>c.settings.minutesPerCredit=9);await store.confirm(install,p.id,{version:p.version});
 $('editForm').dispatchEvent(new window.Event('submit',{cancelable:true}));await settle();
 assert.match($('editorError').textContent,/Settings changed/);assert.equal($('editor').open,true);
 assert.equal(store.get(install).config.settings.waitSeconds,12);assert.equal(store.get(install).config.settings.minutesPerCredit,9);
});

test('browser task edits preserve GPS units and remain reviewable before removal',async t=>{
 const f=await fixture(t),{$,window,store,install}=f;
 window.document.querySelector('[data-edit-task="walk"]').click();
 const form=$('editForm');assert.equal(form.elements.metric.value,'meters');
 form.elements.name.value='Evening walk';form.dispatchEvent(new window.Event('submit',{cancelable:true}));await settle();
 assert.equal(store.get(install).config.tasks.find(t=>t.id==='walk').name,'Take a walk');
 assert.match($('cards').textContent,/Evening walk/);
 window.document.querySelector('[data-test-task]').click();await settle();assert.match($('alert').textContent,/Android app/);
 $('discardChanges').click();await settle();assert.equal($('reviewStatus').textContent,'CANCELLED');
 window.document.querySelector('[data-edit-task="walk"]').click();$('removeTask').click();await settle();
 assert.match($('cards').textContent,/Remove this task/);assert.equal(store.get(install).config.tasks.length,4);
});

test('browser previews proposed rewards without changing the saved balance or rules',async t=>{
 const f=await fixture(t),{$,window,store,install}=f;
 await f.stage(c=>{c.settings.minutesPerCredit=7;c.settings.dailyBudgetMinutes=15;c.tasks[0].unitsPerCredit=4;});
 const saved=structuredClone(store.get(install).config);
 window.document.querySelector('[data-preview="setting:minutesPerCredit"]').click();
 $('previewCredits').value='3';$('previewCredits').dispatchEvent(new window.Event('input'));
 assert.match($('previewOutcome').textContent,/3 × 7 = 21 browsing minutes/);$('returnToReview').click();
 window.document.querySelector('[data-preview="setting:dailyBudgetMinutes"]').click();assert.match($('previewContent').textContent,/15 minutes/);$('closePreview').click();
 window.document.querySelector('[data-preview="task:pushups"]').click();$('previewUnits').value='6';$('previewUnits').dispatchEvent(new window.Event('input'));
 assert.match($('previewOutcome').textContent,/1 credits, 7 minutes, plus 50%/);
 assert.deepEqual(store.get(install).config,saved);
});

test('browser previews session, YouTube, emergency and label changes without sending unlock requests',async t=>{
 const f=await fixture(t),{$,window,requests}=f;
 await f.stage(c=>{c.settings.sessionMinutes=[4,6,8];c.settings.youtubeCredits=5;c.settings.emergencyMinutes=20;c.settings.creditName='stars';c.settings.dayReset='05:30';c.settings.waitSeconds=0;});
 const preview=key=>window.document.querySelector(`[data-preview="setting:${key}"]`).click();
 preview('sessionMinutes');window.document.querySelector('[data-preview-session="6"]').click();assert.match($('previewOutcome').textContent,/6 browsing minutes/);$('closePreview').click();
 preview('youtubeCredits');$('previewCredits').value='2';$('previewCredits').dispatchEvent(new window.Event('input'));assert.match($('previewOutcome').textContent,/3 more stars/);$('previewCredits').value='5';$('previewCredits').dispatchEvent(new window.Event('input'));assert.match($('previewOutcome').textContent,/YouTube is available/);$('closePreview').click();
 preview('emergencyMinutes');$('exampleEmergency').click();assert.match($('previewOutcome').textContent,/20-minute emergency/);$('closePreview').click();
 preview('creditName');$('exampleLabel').click();assert.match($('previewOutcome').textContent,/No unlock or reward was changed/);$('closePreview').click();
 preview('dayReset');assert.match($('previewContent').textContent,/05:30/);$('closePreview').click();
 preview('waitSeconds');$('startPreviewWait').click();assert.equal($('previewCountdown').textContent,'Ready');assert.equal($('startPreviewWait').disabled,false);$('closePreview').click();
 assert(requests.every(r=>['/events','/state'].includes(r.route)));
});

test('browser task creation switches valid metrics and proposals can lose individual cards',async t=>{
 const f=await fixture(t),{$,window,store,install}=f;
 $('addTask').click();const form=$('editForm');
 form.elements.name.value='Run outside';form.elements.verifier.value='gps';form.elements.verifier.dispatchEvent(new window.Event('change'));
 assert.equal(form.elements.metric.value,'meters');assert.equal($('gpsModeField').hidden,false);
 form.elements.gpsMode.value='sprint';form.elements.unitsPerCredit.value='100';form.elements.correctionMinimum.value='1';form.elements.instructions.value='Measure qualifying sprint distance.';
 form.dispatchEvent(new window.Event('submit',{cancelable:true}));await settle();
 assert.equal(store.get(install).config.tasks.length,4);assert.match($('cards').textContent,/Run outside/);
 window.document.querySelector('[data-remove]').click();await settle();assert.equal(store.get(install).revision,0);assert.equal($('reviewStatus').textContent,'CANCELLED');
 await f.stage(c=>{c.settings.waitSeconds=5;c.settings.minutesPerCredit=6;});
 window.document.querySelector('[data-adjust="setting:waitSeconds"]').click();assert.equal($('editForm').elements.waitSeconds.value,'5');assert.equal($('editForm').elements.length,3);$('closeEditor').click();
 window.document.querySelector('[data-remove="setting:waitSeconds"]').click();await settle();assert.equal(store.get(install).proposals.at(-1).cards.length,1);assert.equal($('cardPosition').textContent,'1 / 1');
});

test('browser histories retain old reviews and escape conversation text',async t=>{
 const f=await fixture(t),{$,window,store,install}=f;
 const p=await f.stage(c=>c.settings.waitSeconds=6);await store.confirm(install,p.id,{version:p.version});
 const state=store.get(install);state.messages.push({role:'user',text:'<img src=x onerror=bad()>',at:Date.now()},{role:'assistant',text:'Review & confirm',at:Date.now()+1});
 await f.stage(c=>c.settings.minutesPerCredit=8);
 const history=window.document.querySelector('[data-history-id]');assert(history);assert.equal(history.dataset.historyId,p.id);
 assert.match($('messages').textContent,/<img src=x onerror=bad\(\)>/);assert.equal($('messages').querySelector('img'),null);
 history.open=true;state.messages.push({role:'assistant',text:'Later response',at:Date.now()+2});await f.refresh();assert.equal(window.document.querySelector('[data-history-id]').open,true);
 $('discardChanges').click();await settle();assert.equal(store.get(install).config.settings.minutesPerCredit,3);
});

test('browser assistant settings submit their selected values and report validation failures',async t=>{
 const f=await fixture(t),{$,window,store,install}=f;
 $('assistantReasoning').value='low';$('assistantReasoning').dispatchEvent(new window.Event('change'));assert.equal($('assistantStatus').textContent,'Unsaved changes');
 $('assistantForm').dispatchEvent(new window.Event('submit',{cancelable:true}));await settle();assert.match($('assistantStatus').textContent,/Saved/);assert.equal(store.snapshot(install).assistantSettings.reasoning,'low');assert.equal($('saveAssistant').disabled,false);
 $('assistantReasoning').innerHTML='<option selected value="invalid">invalid</option>';$('assistantForm').dispatchEvent(new window.Event('submit',{cancelable:true}));await settle();assert.doesNotMatch($('assistantStatus').textContent,/Saved/);assert.equal(store.snapshot(install).assistantSettings.reasoning,'low');assert.equal($('saveAssistant').disabled,false);
});

test('browser refuses a task removal based on an obsolete editor revision',async t=>{
 const f=await fixture(t),{$,window,store,install}=f;
 window.document.querySelector('[data-edit-task="walk"]').click();
 const config=structuredClone(store.get(install).config);config.tasks.find(t=>t.id==='walk').name='Updated walk';
 const proposal=await store.stage(install,{config,expectedRevision:0});await store.confirm(install,proposal.id,{version:proposal.version});await f.refresh();
 $('removeTask').click();await settle();
 assert.equal(store.get(install).config.tasks.find(t=>t.id==='walk').name,'Updated walk');assert.equal($('editor').open,true);assert.match($('editorError').textContent,/Settings changed/);
});

test('browser registration persists only the identity and recovers from failed refreshes',async t=>{
 const f=await fixture(t,{register:true}),{$,window,requests,failRoutes}=f;
 assert.equal(requests.filter(r=>r.route==='/installations').length,1);
 const saved=JSON.parse(window.localStorage.getItem('friction.v1.identity'));assert.deepEqual(Object.keys(saved).sort(),['installationId','token']);
 failRoutes.set('/state',{error:'Temporary outage'});await f.refresh();assert.equal($('connection').textContent,'Offline');assert.equal($('alert').textContent,'Temporary outage');
 failRoutes.delete('/state');await f.refresh();assert.equal($('connection').textContent,'Connected');assert.equal(requests.filter(r=>r.route==='/installations').length,1);
 failRoutes.set('/proposals',{});$('editSettings').click();$('editForm').elements.waitSeconds.value='9';$('editForm').dispatchEvent(new window.Event('submit',{cancelable:true}));await settle();assert.match($('editorError').textContent,/request did not finish/);assert.equal($('editor').open,true);
});

test('native review requests a test recording with pinned identity and waits for phone acknowledgement',async t=>{
 let changed=0,closed=0;const recordings=[];
 const f=await fixture(t,{native:{configChanged:()=>changed++,close:()=>closed++,testTask:(...args)=>recordings.push(args)}}),{$,window,store,install}=f;
 assert.equal(changed,1);await f.refresh();assert.equal(changed,1);
 const p=await f.stage(c=>{c.tasks[0].paused=true;c.tasks[0].instructions='Count controlled repetitions.';});
 window.document.querySelector('[data-test-task]').click();await settle();assert.deepEqual(recordings,[[p.id,p.version,'pushups']]);
 $('applyChanges').click();await settle();assert.equal(changed,2);assert.match($('application').textContent,/waiting for your phone/);
 await store.ack(install,1);await f.refresh();assert.match($('application').textContent,/Applied on your phone/);assert.equal($('reviewStatus').textContent,'APPLIED ON YOUR PHONE');
 assert.match($('tasksList').textContent,/Paused/);$('back').click();assert.equal(closed,1);
});

test('voice controls reflect readiness, transcripts, timeout, errors and explicit hangup',async t=>{
 let peer;
 const stream={getTracks:()=>[{stop(){}}],getAudioTracks:()=>[{}]};
 class Peer{
  constructor(){peer=this;}iceGatheringState='complete';localDescription={sdp:'v=0 offer'};
  addTrack(){}createDataChannel(){return this.channel={close(){}};}async createOffer(){return{};}async setLocalDescription(){}
  async setRemoteDescription(){this.channel.onmessage({data:'{"type":"session.started"}'});}close(){}
 }
 class Audio{
  constructor(){const element=document.createElement('audio');element.play=()=>Promise.reject(Error('gesture required'));return element;}
 }
 const f=await fixture(t,{globals:{RTCPeerConnection:Peer,Audio,navigator:{mediaDevices:{getUserMedia:async()=>stream}}}}),{$,window,requests}=f;
 $('composerVoice').click();await settle();assert.equal($('voiceLabel').textContent,'End voice chat');assert.equal($('composerVoice').getAttribute('aria-pressed'),'true');
 const transcript=(role,delta)=>peer.channel.onmessage({data:JSON.stringify({type:`session.${role}_transcript.delta`,delta})});
 transcript('input','Please ');transcript('input','help');assert.equal($('voiceTranscript').textContent,'Please help');transcript('output','Sure');assert.equal($('voiceTranscript').textContent,'Sure');
 $('setupTab').click();transcript('output',' — show review');assert.match($('voiceTranscript').textContent,/show review/);
 peer.ontrack({streams:[stream]});await settle();assert.match($('voiceStatus').textContent,/Press play/);assert.equal(window.document.querySelector('audio').controls,true);
 $('composerVoice').click();await settle();assert.equal($('voiceLabel').textContent,'Start voice chat');assert.equal($('composerVoice').getAttribute('aria-pressed'),'false');assert.equal(requests.filter(r=>r.route==='/voice/close').length,1);
 $('composerVoice').click();await settle();
 f.store.getRuntime(f.install).lastVoiceEnd={sessionId:'fixture-voice',reason:'duration_limit'};
 peer.channel.onmessage({data:'{"type":"session.closed"}'});await settle();
 assert.match($('voiceStatus').textContent,/five-minute limit/);assert.equal($('composerVoice').getAttribute('aria-pressed'),'false');assert.equal($('voice').disabled,false);
 $('voice').click();await settle();peer.channel.onmessage({data:'{"type":"error","error":{"message":"fixture failure"}}'});
 assert.match($('voiceStatus').textContent,/Connection interrupted/);assert.equal($('voice').getAttribute('aria-pressed'),'false');
});

test('browser chat ignores blank messages and submits trimmed text once before restoring the composer',async t=>{
 const f=await fixture(t),{$,window,requests}=f;
 const submit=()=> $('chatForm').dispatchEvent(new window.Event('submit',{cancelable:true}));
 $('message').value='   ';submit();await settle();assert.equal(requests.filter(r=>r.route==='/chat').length,0);
 $('message').value='  Explain my browsing allowance  ';submit();assert.equal($('send').disabled,true);assert.equal($('message').value,'');await settle();
 assert.deepEqual(requests.filter(r=>r.route==='/chat').map(r=>r.body),[{message:'Explain my browsing allowance'}]);assert.equal($('send').disabled,false);assert.match($('messages').textContent,/Explain my browsing allowance/);
});

test('previewing a task deletion preserves the saved task and its history',async t=>{
 const f=await fixture(t),{$,window,store,install}=f;
 await f.stage(c=>{c.tasks=c.tasks.filter(task=>task.id!=='walk');});
 window.document.querySelector('[data-preview="task:walk"]').click();
 assert.match($('previewContent').textContent,/history and earned rewards stay/);
 assert.equal(store.get(install).config.tasks.length,4);assert.equal(store.get(install).revision,0);assert.equal($('previewDialog').open,true);
 $('closePreview').click();assert.equal($('previewDialog').open,false);
});

test('review cards show only changed task fields, escape values and remove edit actions after saving',async t=>{
 const f=await fixture(t),{$,window}=f;
 await f.stage(config=>{config.tasks[0].name='<img src=x onerror=attack()>';});
 const rows=$('cards').querySelectorAll('.change-row');assert.equal(rows.length,1);
 assert.equal(rows[0].querySelector('label').textContent,'Name');
 assert.equal(rows[0].querySelector('.before').textContent,'Push-ups');
 assert.equal(rows[0].querySelector('.after').textContent,'<img src=x onerror=attack()>');
 assert.equal($('cards').querySelector('img'),null);
 assert.equal($('cards').querySelectorAll('[data-adjust]').length,1);
 $('applyChanges').click();await settle();
 assert.equal($('cards').querySelectorAll('[data-adjust],[data-remove],[data-preview],[data-test-task]').length,0);
 assert.equal($('cards').querySelector('.change-card').classList.contains('completed'),true);
});

test('page and visibility restoration fetch current state while hidden pages do not trigger a refresh',async t=>{
 const f=await fixture(t),{window,requests}=f;
 const stateReads=()=>requests.filter(request=>request.route==='/state').length;
 let before=stateReads();window.dispatchEvent(new window.Event('pageshow'));await settle();assert.equal(stateReads(),before+1);
 Object.defineProperty(window.document,'visibilityState',{configurable:true,value:'hidden'});
 before=stateReads();window.document.dispatchEvent(new window.Event('visibilitychange'));await settle();assert.equal(stateReads(),before);
 Object.defineProperty(window.document,'visibilityState',{configurable:true,value:'visible'});
 window.document.dispatchEvent(new window.Event('visibilitychange'));await settle();assert.equal(stateReads(),before+1);
});

test('invalid native bootstrap cannot register a replacement installation or send authenticated requests',async t=>{
 const f=await fixture(t,{native:{bootstrap:()=>'{broken'}}),{$,window,requests}=f;
 assert.equal($('connection').textContent,'Could not connect');assert.equal($('alert').hidden,false);
 window.dispatchEvent(new window.Event('pageshow'));
 Object.defineProperty(window.document,'visibilityState',{configurable:true,value:'visible'});
 window.document.dispatchEvent(new window.Event('visibilitychange'));await settle();assert.equal(requests.length,0);
});

test('polling refreshes stale state while a connected stream suppresses unnecessary reads',async t=>{
 const timers=new Map();let next=100;
 const f=await fixture(t,{globals:{setInterval:(fn,ms)=>{const id=next++;timers.set(id,{fn,ms});return id;},clearInterval:id=>timers.delete(id)}});
 const poll=[...timers.values()].find(timer=>timer.ms===5000).fn;
 let now=Date.now();t.mock.method(Date,'now',()=>now);
 const reads=()=>f.requests.filter(request=>request.route==='/state').length;
 const before=reads();poll();await settle();assert.equal(reads(),before);
 now+=30001;poll();await settle();assert.equal(reads(),before+1);
 const config=structuredClone(f.store.get(f.install).config);config.settings.creditName='stars';
 const proposal=await f.store.stage(f.install,{config,expectedRevision:0});await f.store.confirm(f.install,proposal.id,{version:proposal.version});
 now+=30001;poll();await settle();
 assert.match(f.$('settingsList').textContent,/stars/);
 f.window.dispatchEvent(new f.window.Event('pagehide'));assert.equal(timers.size,0);
});

test('scroll selection sends the visible review card and navigation stays within the rail',async t=>{
 const f=await fixture(t),{$,window,store,install}=f;
 await f.stage(c=>{c.settings.waitSeconds=4;c.settings.minutesPerCredit=5;});
 const rail=$('cards'),cards=[...rail.children];
 cards.forEach((card,i)=>Object.defineProperty(card,'offsetLeft',{value:i*220,configurable:true}));
 rail.scrollTo=({left})=>{rail.scrollLeft=left;rail.dispatchEvent(new window.Event('scroll'));};
 $('nextCard').click();await new Promise(resolve=>setTimeout(resolve,220));
 assert.equal($('cardPosition').textContent,'2 / 2');
 assert.equal(store.get(install).selectedCardId,'setting:minutesPerCredit');
 $('nextCard').click();await new Promise(resolve=>setTimeout(resolve,220));assert.equal(rail.scrollLeft,220);
 $('previousCard').click();await new Promise(resolve=>setTimeout(resolve,220));
 assert.equal(store.get(install).selectedCardId,'setting:waitSeconds');assert.equal($('cardPosition').textContent,'1 / 2');
 rail.replaceChildren();rail.dispatchEvent(new window.Event('scroll'));
 const count=f.requests.length;await new Promise(resolve=>setTimeout(resolve,220));assert.equal(f.requests.length,count);
});

test('task editors retain every evidence mode, unit and availability selection',async t=>{
 const f=await fixture(t),{$,window}=f;
 for(const [id,metric,mode]of [['pushups','repetitions','walk'],['plank','seconds','walk'],['walk','meters','walk'],['sprint','meters','sprint']]){
  window.document.querySelector(`[data-edit-task="${id}"]`).click();
  const form=$('editForm');assert.equal(form.elements.metric.value,metric);assert.equal(form.elements.gpsMode.value,mode);
  assert.equal(form.elements.paused.value,'false');$('closeEditor').click();
 }
 await f.stage(c=>{c.tasks[0].paused=true;});$('applyChanges').click();await settle();
 window.document.querySelector('[data-edit-task="pushups"]').click();assert.equal($('editForm').elements.paused.value,'true');
});

test('test recording results attach only to the matching task and reviewed version',async t=>{
 const f=await fixture(t),{$,store,install}=f;
 const proposal=await f.stage(c=>{c.tasks[0].name='Morning push-ups';c.tasks[1].name='Morning plank';});
 const now=Date.now();
 const attempt=await store.startAttempt(install,{attemptId:'preview-fixture',taskId:'pushups',startedAt:now-10000,configRevision:0,proposalId:proposal.id,proposalVersion:proposal.version});
 const receipt=await store.finishAttempt(install,attempt.attemptId,{endedAt:now,measuredUnits:4,reason:'Four visible repetitions',timeZone:'UTC'});
 assert.equal(receipt.rewardEligible,false);await f.refresh();
 assert.match($('cards').querySelector('[data-card-id="task:pushups"]').textContent,/4 repetitions measured/);
 assert.equal($('cards').querySelector('[data-card-id="task:plank"] .test-result'),null);
 const config=structuredClone(store.get(install).config);config.tasks[0].name='Revised push-ups';
 await store.stage(install,{config,expectedRevision:0});await f.refresh();
 assert.equal($('cards').querySelector('.test-result'),null);assert.equal(store.get(install).revision,0);
});

test('review card classes mark single, saved, undone and cancelled reviews',async t=>{
 const f=await fixture(t),{$,store,install}=f;
 const classes=card=>[...card.classList].sort();
 await f.stage(c=>{c.settings.waitSeconds=7;});
 assert.deepEqual(classes($('cards').querySelector('.change-card')),['change-card','single']);
 $('applyChanges').click();await settle();
 assert.deepEqual(classes($('cards').querySelector('.change-card')),['change-card','completed','single']);
 $('undoChanges').click();await settle();
 const undone=$('cards').querySelector('.change-card');
 assert.deepEqual(classes(undone),['cancelled','change-card','completed','single']);
 assert.match(undone.querySelector('.eyebrow').textContent,/UNDONE/);
 await f.stage(c=>{c.settings.waitSeconds=5;c.settings.minutesPerCredit=9;});
 const pair=$('cards').querySelectorAll('.change-card');
 assert.equal(pair.length,2);assert.deepEqual(classes(pair[0]),['change-card']);
 await store.cancel(install,{});await f.refresh();
 assert.deepEqual(classes($('cards').querySelector('.change-card')),['cancelled','change-card']);
});
