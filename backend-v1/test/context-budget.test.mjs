import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {CHAT_CONTEXT_TOKENS,contextUpperBound,fitChatContext} from '../context-budget.mjs';
import {Agent} from '../agent.mjs';
import {Store} from '../store.mjs';
const request=input=>({instructions:'Product rules',tools:[{name:'read_details'}],input,max_output_tokens:2400,store:false});

test('oversized Unicode history drops oldest complete exchanges, preserving current request and state',()=>{
 const history=Array.from({length:40},(_,i)=>[{role:'user',content:`Request ${i}: `+'界'.repeat(1800)},{role:'assistant',content:`Reply ${i}`}]).flat();
 const current={role:'user',content:'x'.repeat(4900)+' Keep the final instruction intact.'},state={role:'developer',content:'CURRENT CONFIGURATION'};
 const body=request([...history,current,state]),result=fitChatContext(body);
 assert(contextUpperBound(result)<=CHAT_CONTEXT_TOKENS);
 assert(result.input.length<body.input.length);
 assert.equal(result.input[0].role,'user');
 assert.deepEqual(result.input.slice(-2),[current,state]);
 assert(result.input.some(m=>m.content==='Reply 39'));
 assert.equal(body.input.length,82,'does not mutate saved history');
});

test('counts system instructions, tools and output reserve and blocks oversized required state',()=>{
 const body=request([{role:'user',content:'Hello'},{role:'developer',content:'x'.repeat(100000)}]);
 assert.throws(()=>fitChatContext(body),e=>e.status===413);
 assert.throws(()=>fitChatContext({...request([{role:'user',content:'Hello'}]),tools:[{schema:'x'.repeat(100000)}]}),/context limit/);
 const small=request([{role:'user',content:'Hello'}]);assert.deepEqual(fitChatContext(small),small);
});

test('follow-up budgeting retains complete current tool calls and results',()=>{
 const old=[{role:'user',content:'x'.repeat(95000)},{role:'assistant',content:'Earlier reply'}];
 const current=[{role:'user',content:'Read my history'},{role:'developer',content:'Fresh setup'},
 {type:'function_call',name:'read_details',call_id:'c1',arguments:'{"topic":"history"}'},
 {type:'function_call_output',call_id:'c1',output:JSON.stringify({events:['y'.repeat(8000)]})}];
 assert.deepEqual(fitChatContext(request([...old,...current])).input,current);
 assert.throws(()=>fitChatContext(request([...current,{type:'function_call_output',call_id:'c2',output:'x'.repeat(100000)}])),/context limit/);
});

async function fixture(t){const dir=await fs.mkdtemp(path.join(os.tmpdir(),'friction-context-'));t.after(()=>fs.rm(dir,{recursive:true,force:true}));const store=await new Store(dir).init(),{installationId}=await store.register();return{store,installationId};}
const response=output=>({ok:true,status:200,json:async()=>({status:'completed',output})});

test('agent uses budgeted single conversation, including more than 12 messages and full current request',async t=>{
 const {store,installationId:i}=await fixture(t);
 for(let n=0;n<20;n++)await store.message(i,n%2?'assistant':'user','Short exchange '+n);
 const message='x'.repeat(4900)+' Final instruction.';let sent;
 const agent=new Agent(store,{key:'mock',fetcher:async(url,opts)=>{sent=JSON.parse(opts.body);return response([{type:'message',content:[{type:'output_text',text:'Done.'}]}]);}});
 await agent.chat(i,message);
 assert(sent.input.length>12);assert.equal(sent.input.findLast(m=>m.role==='user').content,message);
 assert(contextUpperBound(sent)<=CHAT_CONTEXT_TOKENS);assert.equal(sent.store,false);assert.equal(sent.previous_response_id,undefined);
});

test('oversized tool result stops before another provider call and releases busy state',async t=>{
 const {store,installationId:i}=await fixture(t);store.get(i).statistics.history={events:['界'.repeat(40000)]};let calls=0;
 const agent=new Agent(store,{key:'mock',fetcher:async()=>{calls++;return response([{type:'function_call',name:'read_details',call_id:'history',arguments:'{"topic":"history"}'}]);}});
 await assert.rejects(agent.chat(i,'Show my history'),/context limit/);
 assert.equal(calls,1);assert.equal(store.getRuntime(i).turn,null);assert.equal(store.usage(i).calls,1);
});
