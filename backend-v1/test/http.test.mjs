import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs/promises';import os from 'node:os';import path from 'node:path';import {createApp} from '../server.mjs';import {id,seed} from '../domain.mjs';
async function appFixture(t,options={}){const dir=await fs.mkdtemp(path.join(os.tmpdir(),'friction-http-test-'));const app=await createApp({dataDir:dir,...options});await new Promise(r=>app.server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+app.server.address().port;const call=async(route,body,token)=>{const r=await fetch(base+'/api/v1'+route,{method:body===undefined?'GET':'POST',headers:{...(body===undefined?{}:{'Content-Type':'application/json'}),...(token?{Authorization:'Bearer '+token}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});return{status:r.status,data:await r.json()};};t.after(async()=>{await new Promise(r=>app.server.close(r));await fs.rm(dir,{recursive:true,force:true});});return{...app,base,call};}
test('HTTP app boots, static UI serves, auth enforced and full manual settings flow works',async t=>{const {base,call}=await appFixture(t);assert.equal((await fetch(base+'/health')).status,200);const html=await(await fetch(base)).text();assert(html.includes('Apply')||html.includes('review'));const {data:a}=await call('/installations',{}),{data:b}=await call('/installations',{});assert.equal((await call('/state')).status,401);const config=seed();config.settings.waitSeconds=5;config.tasks[1].name='Morning hold';const p=await call('/proposals',{config,expectedRevision:0},a.token);assert.equal(p.status,201);assert.equal(p.data.cards.length,2);assert.equal((await call(`/proposals/${p.data.id}/confirm`,{version:1},b.token)).status,404);const save=await call(`/proposals/${p.data.id}/confirm`,{version:1,operationId:id()},a.token);assert.equal(save.status,200);assert.equal(save.data.applicationStatus,'awaiting_device');const ack=await call('/ack',{revision:1,configHash:save.data.configHash},a.token);assert.equal(ack.data.applicationStatus,'applied');const undo=await call('/cancel',{proposalId:p.data.id},a.token);assert.equal(undo.data.config.settings.waitSeconds,12);assert.equal(undo.data.revision,2);assert.equal(undo.data.change.verified,true);});
test('HTTP GPS offline bootstrap and retry preserve pinned receipt; fallback explicit',async t=>{const {call}=await appFixture(t);const {data:a}=await call('/installations',{}),attemptId=id(),now=Date.now();const submit={taskId:'walk',startedAt:now-10000,configRevision:0,endedAt:now,points:[],timeZone:'UTC'};const r=await call(`/attempts/${attemptId}/gps`,submit,a.token);assert.equal(r.status,200);assert.equal(r.data.fallbackEligible,true);assert.equal(r.data.measuredUnits,0);assert.equal((await call(`/attempts/${attemptId}/gps`,submit,a.token)).data.id,r.data.id);const fallback=await call(`/attempts/${attemptId}/fallback`,{completed:true},a.token);assert.equal(fallback.data.correctedUnits,100);assert.equal(fallback.data.id,r.data.id);const other=(await call('/installations',{})).data;assert.equal((await call(`/attempts/${attemptId}`,undefined,other.token)).status,404);});
test('HTTP concurrent video retry runs verifier once and accounts usage once',async t=>{let calls=0,release;const gate=new Promise(r=>release=r);const {call,base,store}=await appFixture(t,{openrouterKey:'mock',videoVerifier:async(a,video,{onUsage})=>{calls++;await gate;await onUsage({cost:.03,usage:{prompt_tokens:100}});return{measuredUnits:5,reason:'Test evidence verifier'};}});const {data:a}=await call('/installations',{}),now=Date.now(),attemptId=id();await call('/attempts',{attemptId,taskId:'pushups',startedAt:now-10000,configRevision:0},a.token);const upload=()=>{const f=new FormData();f.set('video',new Blob(['test-mp4'],{type:'video/mp4'}),'test.mp4');f.set('endedAt',String(now));f.set('timeZone','UTC');return fetch(base+`/api/v1/attempts/${attemptId}/video`,{method:'POST',headers:{Authorization:'Bearer '+a.token},body:f}).then(r=>r.json());};const one=upload();while(calls===0)await new Promise(r=>setTimeout(r,5));const two=upload();await new Promise(r=>setTimeout(r,10));release();const [r1,r2]=await Promise.all([one,two]);assert.equal(calls,1);assert.equal(r1.id,r2.id);assert.equal(store.usage().usd,.03);});
test('malformed config and oversized/foreign origin requests cannot mutate',async t=>{const{base,call}=await appFixture(t);const{data:a}=await call('/installations',{});const c=seed();c.settings.doWhatever=true;assert.equal((await call('/proposals',{config:c,expectedRevision:0},a.token)).status,400);const r=await fetch(base+'/api/v1/state',{headers:{Authorization:'Bearer '+a.token,Origin:'https://untrusted.example'}});assert.equal(r.status,403);assert.equal((await call('/state',undefined,a.token)).data.revision,0);});
test('invalid video rejected before provider contact does not consume API budget',async t=>{const {AppError}=await import('../domain.mjs');const{call,base,store}=await appFixture(t,{openrouterKey:'mock',videoVerifier:async()=>{throw new AppError('Invalid MP4.',400);}});const{data:a}=await call('/installations',{}),now=Date.now(),attemptId=id();await call('/attempts',{attemptId,taskId:'pushups',startedAt:now-10000,configRevision:0},a.token);const f=new FormData();f.set('video',new Blob(['bad'],{type:'video/mp4'}),'bad.mp4');f.set('endedAt',String(now));const r=await fetch(base+`/api/v1/attempts/${attemptId}/video`,{method:'POST',headers:{Authorization:'Bearer '+a.token},body:f});assert.equal(r.status,400);assert.equal(store.usage().usd,0);assert.equal(Object.keys(store.db.reservations).length,0);});

test('more than twenty installations from one address are accepted',async t=>{const {call}=await appFixture(t);for(let i=0;i<25;i++)assert.equal((await call('/installations',{})).status,201);});

test('HTTP routes reject malformed or unknown requests and serve explicit static content types',async t=>{
 const {base,call}=await appFixture(t);const {data:a}=await call('/installations',{});
 for(const [route,type]of [['/app','text/html'],['/style.css','text/css'],['/app.js','text/javascript'],['/voice-cue.js','text/javascript']]){
  const response=await fetch(base+route);assert.equal(response.status,200);assert(response.headers.get('content-type').startsWith(type));assert.equal(response.headers.get('x-content-type-options'),'nosniff');
 }
 for(const route of ['/unknown','/api/v1/unknown'])assert.equal((await fetch(base+route,{headers:{Authorization:'Bearer '+a.token}})).status,404);
 assert.equal((await fetch(base+'/api/v1/state',{method:'DELETE',headers:{Authorization:'Bearer '+a.token}})).status,404);
 const invalid=await fetch(base+'/api/v1/proposals',{method:'POST',headers:{Authorization:'Bearer '+a.token,'Content-Type':'application/json'},body:'{'});assert.equal(invalid.status,400);
 const large=await fetch(base+'/api/v1/installations',{method:'POST',body:' '.repeat(4001)});assert.equal(large.status,413);
 const config=await call('/config',undefined,a.token);assert.deepEqual(config.data.config,seed());
 const accepted=await fetch(base+'/api/v1/state',{headers:{Authorization:'Bearer '+a.token,Origin:base}});assert.equal(accepted.status,200);
 assert.equal((await call('/attempts/missing',undefined,a.token)).status,404);
 assert.equal((await call('/missing',{},a.token)).status,404);
 assert.equal((await call('/voice/session',{sdp:'invalid'},a.token)).status,503);
 assert.deepEqual((await call('/voice/close',{sessionId:'missing'},a.token)).data,{ok:true});
});

test('HTTP removing a stale review card cannot apply or drop another revision',async t=>{
 const {call}=await appFixture(t);const {data:a}=await call('/installations',{});const config=seed();config.settings.waitSeconds=4;config.settings.minutesPerCredit=8;
 const {data:p}=await call('/proposals',{config,expectedRevision:0},a.token);
 const remove=await call(`/proposals/${p.id}/remove`,{version:p.version,cardId:'setting:waitSeconds'},a.token);assert.equal(remove.status,200);assert.equal(remove.data.cards.length,1);
 const stale=await call(`/proposals/${p.id}/confirm`,{version:p.version},a.token);assert.equal(stale.status,409);
 const save=await call(`/proposals/${p.id}/confirm`,{version:remove.data.version},a.token);assert.equal(save.status,200);assert.equal(save.data.config.settings.waitSeconds,12);assert.equal(save.data.config.settings.minutesPerCredit,8);
});

test('HTTP context and measured history stay isolated from another installation and cannot grant rewards',async t=>{
 const {call}=await appFixture(t);const {data:a}=await call('/installations',{}),{data:b}=await call('/installations',{});
 const config=seed();config.settings.waitSeconds=7;const {data:p}=await call('/proposals',{config,expectedRevision:0},a.token);
 assert.equal((await call('/context',{selectedCardId:p.cards[0].id},b.token)).status,409);
 assert.equal((await call('/context',{selectedCardId:p.cards[0].id},a.token)).status,200);
 const statistics={summary:{creditsEarnedToday:9,remainingMinutes:30},events:[{kind:'usage',milliseconds:1000}]};
 assert.equal((await call('/statistics',statistics,a.token)).status,200);
 const first=(await call('/state',undefined,a.token)).data,second=(await call('/state',undefined,b.token)).data;
 assert.equal(first.selectedCardId,p.cards[0].id);assert.deepEqual(first.statistics.summary,statistics.summary);assert.deepEqual(first.statistics.events,statistics.events);assert.equal(first.revision,0);assert.deepEqual(first.config,seed());
 assert.equal(second.selectedCardId,null);assert.equal(second.statistics.summary,null);
 assert.equal((await call('/statistics',{summary:[]},a.token)).status,400);
 assert.equal((await call('/context',{selectedCardId:null},a.token)).status,200);
 assert.equal((await call('/state',undefined,a.token)).data.selectedCardId,null);
});

test('empty POST uses an empty object and unexpected request failures do not expose internal details',async t=>{
 const {base,call,store}=await appFixture(t),{data:a}=await call('/installations',{});
 const headers={Authorization:'Bearer '+a.token};
 const empty=await fetch(base+'/api/v1/cancel',{method:'POST',headers});assert.equal(empty.status,200);assert.equal((await empty.json()).restored,false);
 const logged=[];t.mock.method(console,'error',(...args)=>logged.push(args));
 t.mock.method(store,'snapshot',()=>{throw Error('private fixture detail');});
 const failed=await fetch(base+'/api/v1/state',{headers});assert.equal(failed.status,500);
 assert.deepEqual(await failed.json(),{error:'The app could not finish that operation. Try again.'});
 t.mock.method(store,'on',()=>{throw Error('stream setup failed');});
 const stream=await fetch(base+'/api/v1/events',{headers});assert.equal(stream.status,200);assert.equal(await stream.text(),'');
 assert.equal(logged.length,2);
});
