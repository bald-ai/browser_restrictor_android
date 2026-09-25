import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {appOverview, docs, documentationTopics, readAppDocumentation} from '../app-docs.mjs';
import {tools, astraPrompt, livePrompt} from '../prompts.mjs';
import {seed} from '../domain.mjs';
import {Store} from '../store.mjs';
import {Agent} from '../agent.mjs';

test('guide index and read_details schema expose every nonempty topic',()=>{
 const schema=tools.find(t=>t.name==='read_details').parameters.properties.topic.enum;
 assert.deepEqual(schema,[...documentationTopics,'history']);
 for(const [id,content] of Object.entries(docs)){
  assert(content.length>100 && content.length<6000,id);
  assert.match(readAppDocumentation('index'),new RegExp(`- ${id}: `));
  assert.equal(readAppDocumentation(id),content);
 }
 for(const value of ['__proto__','toString','../AGENTS.md','missing',null])assert.throws(()=>readAppDocumentation(value),/Unknown documentation topic/);
});

test('guide settings inventory and factory defaults match configuration',()=>{
 const rows=[...docs.settings.matchAll(/^\| `([^`]+)` \| ([^|]+) \|/gm)];
 const documented=Object.fromEntries(rows.map(([,key,value])=>[key,value.trim()]));
 const expected=Object.fromEntries(Object.entries(seed().settings).map(([key,value])=>[key,Array.isArray(value)?value.join(', '):String(value)]));
 assert.deepEqual(documented,expected);
 assert.match(docs.daily_budget,/0 to 30 whole minutes/);
 assert.match(docs.daily_budget,/current balance untouched/);
 assert.match(astraPrompt,/For app-behavior questions, call read_details/);
 assert(!astraPrompt.includes(docs.video),'Detailed guide should be retrieved rather than copied wholesale into the prompt');
 assert.match(livePrompt({}),/app documentation/);
});

const response=output=>({ok:true,json:async()=>({status:'completed',output})});
const call=(topic,id)=>({type:'function_call',name:'read_details',call_id:id,arguments:JSON.stringify({topic})});

test('agent can discover a guide topic, read details and answer without changing setup',async t=>{
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'friction-guide-'));
 t.after(()=>fs.rm(dir,{recursive:true,force:true}));
 const store=await new Store(dir).init(),{installationId}=await store.register();
 const original=structuredClone(store.get(installationId).config);
 let count=0;
 const agent=new Agent(store,{key:'mock',fetcher:async(url,options)=>{
  const request=JSON.parse(options.body);
  count++;
  if(count===1)return response([call('index','index-call')]);
  const outputs=request.input.filter(item=>item.type==='function_call_output');
  if(count===2){
   const result=JSON.parse(outputs.at(-1).output);
   assert.equal(result.ok,true);assert.match(result.result,/daily_budget:/);
   return response([call('daily_budget','budget-call')]);
  }
  assert.equal(count,3);
  const result=JSON.parse(outputs.at(-1).output);
  assert.equal(result.ok,true);assert.equal(result.result,docs.daily_budget);
  return response([{type:'message',content:[{type:'output_text',text:'The allowance arrives at the next daily reset. Saving it leaves your current balance unchanged.'}]}]);
 }});
 const result=await agent.chat(installationId,'When do I receive my daily allowance?');
 assert.match(result.text,/next daily reset/);
 assert.deepEqual(result.trace.map(t=>t.name),['read_details','read_details']);
 assert.deepEqual(store.get(installationId).config,original);
 assert.equal(store.get(installationId).revision,0);
 assert.equal(store.get(installationId).proposals.length,0);
});

test('history still returns measured statistics rather than static guide text',async t=>{
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'friction-guide-history-'));
 t.after(()=>fs.rm(dir,{recursive:true,force:true}));
 const store=await new Store(dir).init(),{installationId}=await store.register();
 await store.statistics(installationId,{summary:{creditsEarnedToday:2},events:[],history:{dailyUsage:[]}});
 let count=0;
 const agent=new Agent(store,{key:'mock',fetcher:async(url,options)=>{
  if(++count===1)return response([call('history','history-call')]);
  const result=JSON.parse(JSON.parse(options.body).input.find(item=>item.type==='function_call_output').output);
  assert.equal(result.ok,true);
  assert.equal(result.result.summary.creditsEarnedToday,2);
  return response([{type:'message',content:[{type:'output_text',text:'Your latest uploaded statistics show two credits today.'}]}]);
 }});
 await agent.chat(installationId,'How many credits did I earn?');
 assert.equal(count,2);
});


test('voice and configuration prompts preload a concise maintained overview and allow immediate answers',async()=>{
 assert(appOverview.split(/\s+/).length < 400);
 for(const prompt of [astraPrompt,livePrompt({})])assert(prompt.includes(appOverview));
 assert.match(livePrompt({}),/Start with the useful answer/);
 assert.match(livePrompt({}),/answering a simple question covered by the app overview/);
 assert.match(astraPrompt,/Answer basic app-behavior questions directly/);
 const instructions=await fs.readFile(new URL('../../AGENTS.md',import.meta.url),'utf8');
 assert.match(instructions,/docs\/app-overview\.md/);
 assert.match(instructions,/update it in the same change/);
});
