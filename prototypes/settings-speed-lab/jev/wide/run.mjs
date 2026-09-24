import fs from 'node:fs/promises';
import path from 'node:path';
import {parseEnv} from 'node:util';
import {runJev} from './engine.mjs';
import {development,holdout,freshHoldout,allCases} from './cases.mjs';
import {clone,equal,applyCards,diff} from '../../../../backend-v1/domain.mjs';
import {expandChanges} from '../../../../backend-v1/edits.mjs';
import {Store} from '../../../../backend-v1/store.mjs';
import {Agent} from '../../../../backend-v1/agent.mjs';
const dir=path.resolve(import.meta.dirname,'../../results/jev-wide',process.env.RUN_ID||'dev-v1');
await fs.mkdir(dir,{recursive:true});
const file=path.join(dir,'results.json');if(await fs.stat(file).catch(()=>null))throw Error('Choose a new RUN_ID; evidence is immutable.');
const mode=process.env.MODE||'sweep',version=process.env.VERSION||'v1',threshold=Number(process.env.THRESHOLD||.9);
const env=parseEnv(await fs.readFile(path.resolve(import.meta.dirname,'../../../agent-ux-lab/.env'),'utf8'));
const report={started:new Date().toISOString(),mode,version,threshold,protocol:{source:'Current backend-v1 domain, edits, Store, and general Agent; isolated local installations',model:'typesafe-ai/jev; GPT-6 Astra low/fast',timing:'Request to backend proposal/answer ready, includes actual Store staging. No browser paint, voice, Android, or user interaction time.',scoring:'Exact whole configuration for fixed edits; no configuration/review side effects for no-edit. Generated edits checked structurally and text retained for human inspection.',parallel:'Both models start together on independent installations; only selected proposal counted. Losing general branch allowed to finish for evidence; never writes to winning store.'},rows:[]};
await fs.writeFile(path.join(dir,'cases.json'),JSON.stringify(allCases,null,2));
await fs.mkdir(path.join(dir,'source'),{recursive:true});
for(const name of ['engine.mjs','cases.mjs','run.mjs'])await fs.copyFile(path.join(import.meta.dirname,name),path.join(dir,'source',name));
const save=()=>fs.writeFile(file,JSON.stringify(report,null,2));
function state(c){return{message:c.message,config:c.pending?expandChanges(c.config,c.pending):c.config,savedConfig:c.config,selectedCard:c.selectedCard||null,pendingReview:c.pending||null,recentConversation:[]};}
function score(c,result){
 if(result.error)return false;
 if(c.kind==='generated-edit'){const before=c.config.tasks.find(t=>t.id===c.generatedTask),after=result.config?.tasks.find(t=>t.id===c.generatedTask);if(!after?.instructions||after.instructions===before.instructions)return false;const target=clone(c.config);target.tasks.find(t=>t.id===c.generatedTask).instructions=after.instructions;if(c.requiredSetting)target.settings[c.requiredSetting[0]]=c.requiredSetting[1];return equal(result.config,target);}
 if(c.kind==='generated-name'){const name=result.config?.tasks.find(t=>t.id==='walk')?.name;if(!name||name===c.config.tasks.find(t=>t.id==='walk').name)return false;const target=clone(c.config);target.tasks.find(t=>t.id==='walk').name=name;target.tasks.find(t=>t.id===c.requiredPause).paused=true;return equal(result.config,target);}
 return equal(result.config,c.expected);
}
async function makeStore(c,suffix){const store=await new Store(path.join(dir,'stores',c.id+'-'+suffix)).init();const {installationId:id}=await store.register();await store.transaction(()=>{const s=store.get(id);s.config=clone(c.config);s.revisions[0].config=clone(c.config);return{};});if(c.pending)await store.stage(id,{changes:c.pending,expectedRevision:0});if(c.selectedCard){const s=store.get(id);const p=s.proposals.findLast(p=>p.status==='pending');if(p?.cards.some(x=>x.id==='setting:'+c.selectedCard.key))await store.context(id,'setting:'+c.selectedCard.key);else await store.transaction(()=>{store.get(id).messages.push({role:'assistant',text:'The user is currently viewing the '+c.selectedCard.title+' setting.',id:'context',at:Date.now()});return{};});}return{store,id};}
async function verifyStore(store,id,c,config){
 const s=store.get(id);if(!equal(s.config,c.config))throw Error('Saved config changed without approval');
 const p=s.proposals.findLast(p=>p.status==='pending');if(!p)return{review:false};
 if(!equal(applyCards(s.config,p.cards),config))throw Error('Review/config mismatch');
 await store.confirm(id,p.id,{version:p.version});const reloaded=await new Store(store.dir).init();if(!equal(reloaded.get(id).config,config))throw Error('Reload mismatch');await store.cancel(id);if(!equal(store.get(id).config,c.config))throw Error('Undo mismatch');return{review:true,applyReloadUndo:true,cards:p.cards.length};
}
async function general(c,suffix){const {store,id}=await makeStore(c,suffix),calls=[];const a=new Agent(store,{key:env.OPENAI_API_KEY,fetcher:async(url,opts)=>{const t=performance.now(),res=await fetch(url,opts),body=await res.json();calls.push({elapsedMs:performance.now()-t,status:res.status,usage:body.usage,output:body.output});return{ok:res.ok,status:res.status,json:async()=>body};}});const t=performance.now();let text,error;try{text=(await a.chat(id,c.message)).text;}catch(e){error=e.message;}const elapsedMs=performance.now()-t,s=store.get(id),p=s.proposals.findLast(p=>p.status==='pending');const config=p?applyCards(s.config,p.cards):s.config;return{elapsedMs,readyAt:performance.now(),text,error,config,calls,audit:()=>verifyStore(store,id,c,config)};}
async function jev(c,suffix){const result=await runJev(state(c),{version,threshold});if(result.accepted){const {store,id}=await makeStore(c,suffix);try{const t=performance.now();if(!result.noOp)await store.stage(id,{changes:result.changes,expectedRevision:0});result.elapsedMs+=performance.now()-t;result.audit=()=>verifyStore(store,id,c,result.config);}catch(e){result.accepted=false;result.reason='stage-error';result.error=e.message;}}result.readyAt=performance.now();return result;}
async function audit(result){if(!result?.audit)return;try{result.verification=await result.audit();}catch(e){result.error=e.message;result.auditFailed=true;}delete result.audit;}
const selection=process.env.SPLIT==='fresh'?freshHoldout:process.env.SPLIT==='holdout'?holdout:development;
const selectedIds=process.env.CASES?.split(',');const chosen=selectedIds?allCases.filter(c=>selectedIds.includes(c.id)):selection;
const repetitions=Number(process.env.REPEATS||1);
try{for(let rep=0;rep<repetitions;rep++)for(const c of chosen){
 if(mode==='sweep'){
  const result=await jev(c,`sweep-${rep}`);await audit(result);const correct=result.accepted?score(c,result)&&!result.auditFailed:null;
  report.rows.push({id:c.id,category:c.category,kind:c.kind,message:c.message,rep,expected:c.expected,expectedRoute:c.route,...result,correct,decisionCorrect:result.accepted?equal(result.config,c.expected):null,routeCorrect:c.route?result.route===c.route:null});
  console.log(`${c.id}: ${result.accepted?(correct?'PASS':'WRONG'):'FALLBACK '+result.reason} ${Math.round(result.elapsedMs)}ms`);
 }else{
  const strategies=rep%2?['parallel','jev-first','general']:['general','jev-first','parallel'];
  for(const strategy of strategies){let j,g,chosenResult,pathUsed;const t=performance.now();
   if(strategy==='general'){g=await general(c,`${strategy}-${rep}`);chosenResult=g;pathUsed='general';}
   else if(strategy==='jev-first'){j=await jev(c,`${strategy}-j-${rep}`);if(j.accepted){chosenResult=j;pathUsed='jev';}else{g=await general(c,`${strategy}-g-${rep}`);chosenResult=g;pathUsed='general';}}
   else{const gp=general(c,`${strategy}-g-${rep}`),jp=jev(c,`${strategy}-j-${rep}`);const first=await Promise.race([gp.then(result=>({path:'general',result})),jp.then(result=>({path:'jev',result}))]);if(first.path==='general'){chosenResult=first.result;pathUsed='general';}else if(first.result.accepted){chosenResult=first.result;pathUsed='jev';}else{chosenResult=await gp;pathUsed='general';} [j,g]=await Promise.all([jp,gp]);}
   const elapsedMs=chosenResult.readyAt-t;await audit(j);await audit(g);const row={id:c.id,category:c.category,kind:c.kind,message:c.message,rep,strategy,path:pathUsed,elapsedMs,correct:score(c,chosenResult)&&!chosenResult.auditFailed,expected:c.expected,result:chosenResult,jev:j,general:g};report.rows.push(row);console.log(`${strategy} ${c.id}: ${row.correct?'PASS':'FAIL'} ${Math.round(elapsedMs)}ms ${pathUsed}`);await save();
  }
 }
 await save();
}report.status='complete';}catch(e){report.status='stopped';report.error=e.stack;console.error(e);}finally{report.finished=new Date().toISOString();await save();}
