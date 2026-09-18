import fs from 'node:fs/promises';import assert from 'node:assert/strict';
const base='http://localhost:4317';
const post=async(route,body)=>{const r=await fetch(base+route,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const j=await r.json();if(!r.ok)throw Error(j.error);return j;};
const get=()=>fetch(base+'/api/state').then(r=>r.json());const evidence=[];
async function ask(message,options={}){const r=await post('/api/chat',{message,...options});console.log(JSON.stringify({message,reply:r.text,seconds:r.turn.elapsedMs/1000,cost:r.turn.cost?.usd,functions:r.turn.toolTrace.map(t=>t.name)}));evidence.push(r);return r;}
await post('/api/new',{model:'gpt-5.6-terra',label:'Task setup and clarification'});
let s=await get();const originalRevision=s.revision;
let r=await ask('I want to earn time by brushing my teeth. Help me set it up.');
assert.equal((await get()).revision,originalRevision);assert.ok(r.turn.toolTrace.some(t=>t.name==='search_product_docs'));assert.ok(!r.turn.toolTrace.some(t=>t.name==='draft_task'));
r=await ask('Name it Brush teeth. Use video, measure active seconds, 30 seconds per credit. Require at least one second of visible brushing before I can correct the result. Count actual brushing with the toothbrush at my mouth, not just holding it.');
s=await get();assert.equal(s.revision,originalRevision);assert.ok(s.drafts.some(d=>d.status==='pending'&&d.task?.name==='Brush teeth'));
r=await ask('Actually make it 20 seconds per credit. Keep the rest the same.');
s=await get();const d=s.drafts.find(d=>d.status==='pending');assert.equal(d.task.unitsPerCredit,20);assert.equal(s.config.tasks.some(t=>t.name==='Brush teeth'),false);
await fs.writeFile('results/functions-v2/task-setup-evidence.json',JSON.stringify(evidence,null,2));console.log('REVIEW_READY',d.id);
