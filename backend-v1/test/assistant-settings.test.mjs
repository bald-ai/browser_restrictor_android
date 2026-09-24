import test from 'node:test';import assert from 'node:assert/strict';
import fs from 'node:fs/promises';import os from 'node:os';import path from 'node:path';
import {Store} from '../store.mjs';import {Agent} from '../agent.mjs';import {createApp} from '../server.mjs';
import {assistantModels,defaultAssistantSettings,validateAssistantSettings,estimatedModelCost} from '../assistant-settings.mjs';
async function fixture(t){const dir=await fs.mkdtemp(path.join(os.tmpdir(),'friction-models-'));t.after(()=>fs.rm(dir,{recursive:true,force:true}));const store=await new Store(dir).init();const {installationId:i,token}=await store.register();return{store,i,token,dir};}
const response=output=>({ok:true,json:async()=>({status:'completed',service_tier:'default',usage:{input_tokens:100,output_tokens:10,input_tokens_details:{cached_tokens:0,cache_write_tokens:0}},output})});
const answer=[{type:'message',content:[{text:'Done.'}]}];
test('existing installations default to Astra Low Fast; preferences persist independently per installation',async t=>{
 const{store,i,dir}=await fixture(t);const other=await store.register();const before=store.snapshot(i);assert.deepEqual(before.assistantSettings,defaultAssistantSettings);
 const settings={model:'gpt-5.6-luna',reasoning:'none',serviceTier:'default'};await store.setAssistantSettings(i,settings);
 const loaded=await new Store(dir).init();assert.deepEqual(loaded.snapshot(i).assistantSettings,settings);assert.deepEqual(loaded.snapshot(other.installationId).assistantSettings,defaultAssistantSettings);assert.deepEqual(loaded.snapshot(i).config,before.config);assert.equal(loaded.snapshot(i).revision,before.revision);assert.deepEqual(loaded.snapshot(i).proposals,before.proposals);
});
test('reject unsupported models, effort/tier combinations and extra fields without mutation',async t=>{
 const{store,i}=await fixture(t);
 for(const value of [null,{}, {...defaultAssistantSettings,model:'unknown'}, {...defaultAssistantSettings,reasoning:'none'}, {...defaultAssistantSettings,reasoning:'ultra'}, {...defaultAssistantSettings,serviceTier:'ultrafast'}, {...defaultAssistantSettings,extra:true}])await assert.rejects(store.setAssistantSettings(i,value));
 assert.deepEqual(store.snapshot(i).assistantSettings,defaultAssistantSettings);
});
for(const m of assistantModels)for(const effort of m.efforts)test(`${m.label} ${effort} sends the saved model, effort and standard tier for voice delegation`,async t=>{
 const{store,i}=await fixture(t);const settings={model:m.id,reasoning:effort,serviceTier:'default'};await store.setAssistantSettings(i,settings);let body;
 const agent=new Agent(store,{key:'mock',jevKey:null,fetcher:async(url,opts)=>{body=JSON.parse(opts.body);return response(answer);}});
 await agent.chat(i,'Explain my setup',{source:'voice'});assert.equal(body.model,m.id);assert.equal(body.reasoning.effort,effort);assert.equal(body.service_tier,'default');assert(body.max_output_tokens>=2400);if(['high','xhigh','max'].includes(effort))assert(body.max_output_tokens>=16384);
 const usage=store.usage(i).entries.at(-1);assert.equal(usage.model,m.id);assert.equal(usage.reasoning,effort);assert.equal(usage.serviceTier,'default');
});
test('Jev fallback uses selected Terra; preference changes during the request apply to the next request',async t=>{
 const{store,i}=await fixture(t);let enter,release;const ready=new Promise(r=>enter=r),gate=new Promise(r=>release=r);let evaluations=0;const bodies=[];
 await store.setAssistantSettings(i,{model:'gpt-5.6-terra',reasoning:'max',serviceTier:'fast'});
 const agent=new Agent(store,{key:'mock',jevKey:null,jevEvaluator:async()=>{if(!evaluations++){enter();await gate;}return{answers:{}};},fetcher:async(url,opts)=>{bodies.push(JSON.parse(opts.body));return response(answer);}});
 const turn=agent.chat(i,'Explain my setup');await ready;await store.setAssistantSettings(i,{model:'gpt-5.6-luna',reasoning:'none',serviceTier:'default'});release();await turn;
 assert.equal(bodies[0].model,'gpt-5.6-terra');assert.equal(bodies[0].reasoning.effort,'max');assert.equal(bodies[0].service_tier,'fast');await agent.chat(i,'Explain it again');assert.equal(bodies[1].model,'gpt-5.6-luna');assert.equal(bodies[1].reasoning.effort,'none');assert.equal(bodies[1].service_tier,'default');
});
test('cost accounting uses model rates and actual returned processing tier',()=>{
 const usage={input_tokens:1000000,output_tokens:1000000,input_tokens_details:{cached_tokens:0,cache_write_tokens:0}};
 assert.equal(estimatedModelCost({model:'gpt-5.6-luna',serviceTier:'fast'},usage,'default'),1.4);
 assert.equal(estimatedModelCost({model:'gpt-5.6-terra',serviceTier:'fast'},usage,'priority'),28);
 assert.equal(estimatedModelCost(defaultAssistantSettings,usage,'fast'),120);
});
test('authenticated HTTP preferences validate and save without app configuration changes',async t=>{
 const{store,i,token}=await fixture(t);const{server}=await createApp({store,openaiKey:'test'});await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>{server.closeAllConnections();server.close(r);}));const base=`http://127.0.0.1:${server.address().port}`;
 const settings={model:'gpt-5.6-terra',reasoning:'none',serviceTier:'fast'};
 let res=await fetch(base+'/api/v1/assistant-settings',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(settings)});assert.equal(res.status,401);
 res=await fetch(base+'/api/v1/assistant-settings',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(settings)});assert.equal(res.status,200);const saved=await res.json();assert.deepEqual(saved.assistantSettings,settings);assert.equal(saved.revision,0);assert(saved.assistantOptions.find(m=>m.id==='gpt-5.6-luna').efforts.includes('none'));
 res=await fetch(base+'/api/v1/assistant-settings',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify({...settings,reasoning:'ultra'})});assert.equal(res.status,400);assert.deepEqual(store.snapshot(i).assistantSettings,settings);
});
