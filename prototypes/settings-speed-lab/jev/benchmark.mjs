import {chromium} from 'playwright';import fs from 'node:fs/promises';import path from 'node:path';import assert from 'node:assert/strict';
import {seed,clone,applyCards} from '../frozen/domain.mjs';
const root=import.meta.dirname,resultDir=path.resolve(root,'../results/jev',process.env.JEV_RUN_ID||'interactive');await fs.mkdir(resultDir,{recursive:true});const file=path.join(resultDir,process.env.JEV_HOLDOUT==='1'?'heldout.json':'benchmark.json');if(await fs.stat(file).catch(()=>null))throw Error('Run ID already has benchmark evidence; choose a new ID.');
const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true,args:['--mute-audio']});
const report={startedAt:new Date().toISOString(),status:'Running',protocol:{comparison:'Frozen focused-edit general agent vs Jev bounded choices plus the same general agent for other requests',generalModel:'gpt-6-astra low / fast',jevModel:'typesafe-ai/jev through Vercel Gateway',order:'Sequential alternating AB/BA; independent fresh browser installations; three repetitions per edit case',timing:'Browser send to visible review; full API/agent time; Apply to response. Existing 1200ms polling unchanged.',excluded:'Android acknowledgement, voice latency, real blocking',selectionThreshold:.9,jevQuestionDesign:'Separate explicit-change booleans from value choices; ignore value distributions for clearly unchanged fields'},warmups:[],trials:[]};
const median=values=>{const a=[...values].sort((x,y)=>x-y);return a.length?(a[Math.floor((a.length-1)/2)]+a[Math.floor(a.length/2)])/2:null;};
async function write(){const summary={};for(const variant of ['general','jev']){const rows=report.trials.filter(r=>r.variant===variant),edits=rows.filter(r=>r.passed&&r.kind==='edit');summary[variant]={passed:rows.filter(r=>r.passed).length,total:rows.length,editCount:edits.length,medianReviewMs:median(edits.map(r=>r.visibleMs)),medianAgentMs:median(edits.map(r=>r.metric?.elapsedMs)),medianSaveMs:median(edits.map(r=>r.buttonSaveMs).filter(Number.isFinite)),paths:rows.reduce((a,r)=>(a[r.metric?.path||'unknown']=(a[r.metric?.path||'unknown']||0)+1,a),{}),estimatedUsd:[...rows,...report.warmups.filter(r=>r.variant===variant)].reduce((n,r)=>n+(r.metric?.estimatedUsd||0),0)};}summary.byCase=[...new Set(report.trials.filter(r=>r.kind==='edit').map(r=>r.name.replace(/-r[0-9]+$/,'')))].map(name=>({name,...Object.fromEntries(['general','jev'].map(v=>{const rows=report.trials.filter(r=>r.variant===v&&r.name.replace(/-r[0-9]+$/,'')===name);return[v,{passed:rows.filter(r=>r.passed).length,total:rows.length,medianReviewMs:median(rows.filter(r=>r.passed).map(r=>r.visibleMs)),medianAgentMs:median(rows.filter(r=>r.passed).map(r=>r.metric?.elapsedMs))}];}))}));report.summary=summary;await fs.writeFile(file,JSON.stringify(report,null,2));}
async function session(variant){const context=await browser.newContext({viewport:{width:430,height:900}}),page=await context.newPage();page.setDefaultTimeout(15000);await page.goto('http://localhost:'+(variant==='general'?4341:4342));await page.waitForFunction(()=>document.querySelector('#connection').textContent==='Connected');const identity=await page.evaluate(()=>JSON.parse(localStorage.getItem('friction.v1.identity')));const base=new URL(page.url()).origin;const api=async(route,body)=>{const res=await fetch(base+route,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+identity.token,'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)})});const data=await res.json();assert(res.ok,JSON.stringify(data));return data;};return{variant,context,page,api,state:()=>api('/api/v1/state')};}
async function clickAction(s,selector,route){const before=performance.now(),response=s.page.waitForResponse(r=>r.url().includes(route)&&r.request().method()==='POST');await s.page.locator(selector).click();const r=await response;assert(r.ok(),await r.text());await s.page.waitForTimeout(120);return performance.now()-before;}
async function trial(s,{name,message,expected,kind='edit',warmup=false,apply=true,undo=false}){
 if(s.variant==='jev'){const remaining=nextJevAt-Date.now();if(remaining>0){console.log(`Pacing Jev: ${Math.ceil(remaining/1000)}s`);await new Promise(r=>setTimeout(r,remaining));}nextJevAt=Date.now()+Number(process.env.JEV_INTERVAL_MS||15000);}
 const before=await s.state(),row={variant:s.variant,name,kind,message,startedAt:new Date().toISOString(),passed:false};
 try{
  await s.page.locator('#message').fill(message);
  // Observe actual DOM review/message updates, including updates arriving before /chat completes.
  await s.page.evaluate(()=>{window.labVisibleAt=null;window.labStartedAt=performance.now();const prior=document.querySelector('#review').innerHTML;const messages=document.querySelector('#messages').innerHTML;window.labObserver?.disconnect();window.labObserver=new MutationObserver(()=>{if(window.labVisibleAt===null&&(document.querySelector('#review').innerHTML!==prior||document.querySelector('#messages').innerHTML!==messages&&document.querySelector('#messages').lastElementChild?.classList.contains('assistant'))){window.labVisibleAt=performance.now()-window.labStartedAt;}});window.labObserver.observe(document.querySelector('#review'),{subtree:true,childList:true,characterData:true});window.labObserver.observe(document.querySelector('#messages'),{subtree:true,childList:true,characterData:true});});
  const response=s.page.waitForResponse(r=>r.url().endsWith('/api/v1/chat')&&r.request().method()==='POST',{timeout:90000});
  const started=performance.now();await s.page.locator('#send').click();const res=await response;row.httpMs=performance.now()-started;row.reply=await res.json();assert(res.ok(),JSON.stringify(row.reply));
  await s.page.waitForFunction(()=>window.labVisibleAt!==null,{},{timeout:10000});row.visibleMs=await s.page.evaluate(()=>window.labVisibleAt);await s.page.waitForFunction(()=>!document.querySelector('#send').disabled);
  const state=await s.state();row.assistantText=state.messages.at(-1)?.text;const proposal=state.proposals.findLast(p=>p.status==='pending');
  assert.deepEqual(state.config,before.config,'Saved config changed before approval');assert.equal(state.revision,before.revision,'Revision changed before approval');
  if(kind==='edit'){
   assert(proposal,'Expected a review');const desired=applyCards(state.config,proposal.cards);if(typeof expected==='function')expected(desired,before.config);else assert.deepEqual(desired,expected,'Proposed configuration differs from requested edits');row.cards=proposal.cards; row.path=row.metric?.path;assert.equal(await s.page.locator('#cards .change-card').count(),proposal.cards.length);
   if(apply){row.buttonSaveMs=await clickAction(s,'#applyChanges','/confirm');const saved=await s.state();assert.deepEqual(saved.config,desired);assert.equal(saved.changes.at(-1).verified,true,'Read-back not verified');row.savedRevision=saved.revision;await s.page.reload();await s.page.waitForFunction(()=>document.querySelector('#connection').textContent==='Connected');assert.deepEqual((await s.state()).config,desired);if(undo){await clickAction(s,'#undoChanges','/cancel');assert.deepEqual((await s.state()).config,before.config);}}
  }else{assert.equal(proposal,undefined,'Should not propose an inferred/invalid change');}
  row.passed=true;
 }catch(e){row.error=e.message;await s.page.screenshot({path:path.join(resultDir,`${s.variant}-${name}-failure.png`),fullPage:true}).catch(()=>{});}
 finally{const metrics=await s.api('/lab/metrics');row.metric=metrics.at(-1);delete row.reply;(warmup?report.warmups:report.trials).push(row);await write();console.log(`${row.passed?'PASS':'FAIL'} ${s.variant} ${name} ${Math.round(row.visibleMs||row.httpMs||0)}ms${row.error?' '+row.error.slice(0,120):''}`);}
 if(row.metric?.calls.some(c=>c.status===429||c.status===403))throw Error("Provider access/rate limit; stopped after preserving the failed trial.");
 return row;
}
let pairIndex=0,nextJevAt=0;
async function pair(fn){for(const variant of pairIndex++%2?['jev','general']:['general','jev']){const s=await session(variant);try{await fn(s);}finally{await s.context.close();}}}
const expected=(fn)=>{const c=seed();fn(c);return c;};
const cases=[
{name:'pause-walk',message:'Pause the walking task for now.',expected:expected(c=>c.tasks[3].paused=true)},
{name:'waiting-time',message:'Make me wait seven seconds before opening the browser.',expected:expected(c=>c.settings.waitSeconds=7)},
{name:'reward-size',message:'Give me six minutes of browsing for each credit I earn.',expected:expected(c=>c.settings.minutesPerCredit=6)},
{name:'youtube-threshold',message:'Require 5 credits before YouTube opens.',expected:expected(c=>c.settings.youtubeCredits=5)},
{name:'grouped',message:'Make the wait 8 seconds and pause Plank. Keep everything else.',expected:expected(c=>{c.settings.waitSeconds=8;c.tasks[1].paused=true;})}
];
try{
 if(process.env.JEV_HOLDOUT==='1'){
 report.protocol.order='New wording and edge cases selected after integration was frozen; one paired run each, alternating order';
 const holdouts=[
 {name:'paraphrase-reward',message:'For every credit I earn, let me browse for nine minutes.',expected:expected(c=>c.settings.minutesPerCredit=9)},
 {name:'three-edits',message:'I want an eighteen-second pause before browsing and YouTube after six credits. Also disable the sprint task.',expected:expected(c=>{c.settings.waitSeconds=18;c.settings.youtubeCredits=6;c.tasks[2].paused=true;})},
 {name:'negated-target',message:'Do not pause walking. Pause the plank task instead.',expected:expected(c=>c.tasks[1].paused=true)},
 {name:'large-wait',message:'Set the waiting time to 400 seconds.',expected:expected(c=>c.settings.waitSeconds=400)},
 {name:'mixed-delegation',message:'Set waiting time to 9 seconds and rename Take a walk to Evening stroll.',expected:expected(c=>{c.settings.waitSeconds=9;c.tasks[3].name='Evening stroll';})},
 {name:'broad-intent',message:'Make this app a bit less strict. What would you suggest? Do not change settings yet.',kind:'clarification'}
 ];
 for(const c of holdouts)await pair(s=>trial(s,{...c,undo:true}));
 }else{
 await pair(s=>trial(s,{name:'warmup',message:'Set the emergency unlock to 11 minutes.',expected:expected(c=>c.settings.emergencyMinutes=11),warmup:true,undo:true}));
 for(let repeat=0;repeat<3;repeat++)for(const c of cases)await pair(s=>trial(s,{...c,name:c.name+'-r'+repeat,undo:true}));
 await pair(async s=>{await trial(s,{name:'pending-first',message:'Set waiting time to 8 seconds and pause Plank.',expected:cases[4].expected,apply:false});await s.api('/api/v1/context',{selectedCardId:'setting:waitSeconds'});await trial(s,{name:'pending-correction',message:'Actually make the waiting time 11 seconds. Keep the other proposed change.',expected:expected(c=>{c.settings.waitSeconds=11;c.tasks[1].paused=true;}),undo:true});});
 await pair(async s=>{const config=expected(c=>c.tasks[3].paused=true);const p=await s.api('/api/v1/proposals',{config,expectedRevision:0});await s.api('/api/v1/proposals/'+p.id+'/confirm',{version:p.version});await s.page.reload();await s.page.waitForFunction(()=>document.querySelector('#connection').textContent==='Connected');await trial(s,{name:'resume-walk',message:'Turn the walking task back on.',expected:seed(),undo:true});});
 for(const[name,message]of [['ambiguous','Make this 7.'],['invalid','Set waiting time to negative 5 seconds.'],['question','What would happen if I paused walking? Do not change anything.']])await pair(s=>trial(s,{name,message,kind:'clarification'}));
 await pair(s=>trial(s,{name:'rename-delegation',message:'Rename Take a walk to Fresh air. Keep everything else.',expected:expected(c=>c.tasks[3].name='Fresh air'),undo:true}));
 }
 report.status='Complete';report.finishedAt=new Date().toISOString();
}catch(e){report.status='Stopped: '+e.message;console.error(e);}finally{await write();await browser.close();}
