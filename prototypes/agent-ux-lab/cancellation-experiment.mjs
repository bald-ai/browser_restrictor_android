import assert from 'node:assert/strict';import fs from 'node:fs/promises';
const post=async(route,body={})=>{const r=await fetch('http://localhost:4317/api/'+route,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const j=await r.json();if(!r.ok)throw Error(j.error);return j;};
const get=()=>fetch('http://localhost:4317/api/state').then(r=>r.json()),evidence=[];
await post('reset');await post('new',{label:'Astra Fast · cancellation tests'});
let r=await post('chat',{message:'Set the waiting time to 5 seconds.'});evidence.push(r);let s=await get();assert.equal(s.config.settings.waitSeconds,12);
r=await post('cancel',{draftId:s.drafts.find(d=>d.status==='pending').id});evidence.push(r);assert.equal(r.verified,true);assert.equal((await get()).drafts.at(-1).status,'cancelled');console.log('PASS pending cancellation');
r=await post('chat',{message:'Set the waiting time to 8 seconds.'});evidence.push(r);s=await get();r=await post('approve',{draftId:s.drafts.find(d=>d.status==='pending').id});evidence.push(r);assert.equal(r.config.settings.waitSeconds,8);
r=await post('chat',{message:'Please undo my most recent setting adjustment.'});evidence.push(r);assert.ok(r.turn.toolTrace.some(t=>t.name==='cancel_change'));assert.equal(r.config.settings.waitSeconds,12);assert.ok(r.turn.verifiedChanges.length);console.log('PASS model-called undo and verification');
const before=(await get()).revision;const inflight=post('chat',{message:'Set the waiting time to 11 seconds.'});
for(let i=0;i<50;i++){if((await get()).busy)break;await new Promise(r=>setTimeout(r,20));}
r=await post('cancel');evidence.push(r);const original=await inflight;evidence.push(original);assert.equal(original.cancelled,true);s=await get();assert.equal(s.revision,before);assert.equal(s.drafts.filter(d=>d.status==='pending').length,0);console.log('PASS cancellation during real model request; no late proposal');
await fs.writeFile('results/cancellation/text-evidence.json',JSON.stringify(evidence,null,2));
