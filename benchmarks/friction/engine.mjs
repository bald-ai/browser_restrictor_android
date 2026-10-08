import './models.mjs';
import fs from 'node:fs/promises';
import path from 'node:path';
import {performance} from 'node:perf_hooks';
import {Store} from '../../backend-v1/store.mjs';
import {Agent,contextFor} from '../../backend-v1/agent.mjs';
import {clone,equal,applyCards,isCancel,isApproval} from '../../backend-v1/domain.mjs';
import {validateAssistantSettings,reserveModelCost,estimatedModelCost} from '../../backend-v1/assistant-settings.mjs';

export class BudgetExceeded extends Error {}
export class Meter {
 constructor({maxUsd,maxCalls}){this.maxUsd=maxUsd;this.maxCalls=maxCalls;this.spent=0;this.reserved=0;this.calls=[];}
 reserve(amount,detail){
  if(!Number.isFinite(amount)||amount<0)throw Error('Invalid cost reservation');
  if(this.calls.length>=this.maxCalls||this.spent+this.reserved+amount>this.maxUsd)throw new BudgetExceeded('Run budget exhausted before provider call');
  const call={...detail,reservedUsd:amount,status:'running'};this.calls.push(call);this.reserved+=amount;this.onEvent?.({type:'call-started',call:clone(call)});return call;
 }
 finish(call,usd,detail){this.reserved-=call.reservedUsd;call.usd=Number.isFinite(usd)&&usd>=0?usd:call.reservedUsd;call.estimated=true;Object.assign(call,detail);this.spent+=call.usd;this.onEvent?.({type:'call-finished',call:clone(call)});}
}
export function validateStrategy(s,depth=0){
 if(!s||depth>5)throw Error('Invalid strategy tree');
 if(s.kind==='llm'){validateAssistantSettings({model:s.model,reasoning:s.reasoning,serviceTier:s.serviceTier});return;}
 if(!['cascade','race'].includes(s.kind)||!Array.isArray(s.steps)||s.steps.length<2||s.steps.length>8)throw Error('Use llm, cascade or race with 2–8 steps');
 s.steps.forEach(step=>validateStrategy(step,depth+1));
}
export async function fixture(dir,c){
 const store=await new Store(dir).init();const{installationId:install}=await store.register();
 await store.transaction(()=>{const s=store.get(install);s.config=clone(c.initial);s.revisions[0].config=clone(c.initial);return{};});
 if(c.pending)await store.stage(install,{config:c.pending,expectedRevision:0,title:'Existing review'});
 if(c.selectedCardId)await store.context(install,c.selectedCardId);
 for(const m of c.history||[])await store.message(install,m.role,m.text);
 return{store,install};
}
async function forkState(base,dir){
 const store=await new Store(dir).init();store.db=clone(base.store.db);await store.persist();return{store,install:base.install};
}
const pendingConfig=state=>{const s=state.store.get(state.install),p=s.proposals.findLast(p=>p.status==='pending');return p?applyCards(s.config,p.cards):null;};
export function observation(state){const s=state.store.get(state.install);return{saved:clone(s.config),review:pendingConfig(state),revision:s.revision};}
export async function runStrategy(strategy,base,message,env){
 const started=performance.now();
 if(strategy.kind==='cascade'||strategy.kind==='race'){
  const steps=strategy.steps.map((step,index)=>()=>runStrategy(step,base,message,{...env,dir:path.join(env.dir,String(index))}));
  let branches=[],winner;
  if(strategy.kind==='cascade'){
   for(const run of steps){const b=await run();branches.push(b);if(b.status==='resolved'){winner=b;break;}if(b.status==='budget')break;}
  }else{
   // First usable result wins without consulting the scorer. Losing calls finish
   // for complete cost accounting and can only mutate their own local store.
   const running=steps.map(run=>run().then(b=>{if(!winner&&b.status==='resolved')winner=b;return b;}));
   branches=await Promise.all(running);
  }
  return{status:winner?'resolved':branches.some(b=>b.status==='budget')?'budget':branches.every(b=>b.status==='error')?'error':'abstained',state:winner?.state||base,text:winner?.text||'',selected:winner?.selected||null,readyAt:winner?.readyAt||performance.now(),elapsedMs:(winner?.readyAt||performance.now())-started,branches};
 }
 const state=await forkState(base,env.dir),{store,install}=state;
 try{
  const settings={model:strategy.model,reasoning:strategy.reasoning,serviceTier:strategy.serviceTier};await store.setAssistantSettings(install,settings);
  const fetcher=async(url,options)=>{
   const body=JSON.parse(options.body);const call=env.meter.reserve(reserveModelCost(settings,body),{trial:env.trial,kind:'llm',model:body.model,reasoning:body.reasoning.effort,requestedTier:body.service_tier,request:body});const start=performance.now();
   try{const res=await(env.fetcher||fetch)(url,{...options,signal:AbortSignal.any([options.signal,AbortSignal.timeout(env.timeoutMs)])});const data=await res.json();env.meter.finish(call,data.usage?estimatedModelCost(settings,data.usage,data.service_tier||settings.serviceTier):res.status>=400&&res.status<500?0:null,{status:res.ok?'completed':'error',httpStatus:res.status,elapsedMs:performance.now()-start,actualModel:data.model||body.model,actualTier:data.service_tier,usage:data.usage,output:data.output,error:data.error?.message});return{ok:res.ok,status:res.status,json:async()=>data};}
   catch(e){env.meter.finish(call,null,{status:'error',elapsedMs:performance.now()-start,error:e.message});throw e;}
  };
  const result=await new Agent(store,{key:env.openaiKey||'injected-test',fetcher}).chat(install,message);
  return{status:'resolved',state,text:result.text,selected:strategy.model,readyAt:performance.now(),elapsedMs:performance.now()-started};
 }catch(e){return{status:e instanceof BudgetExceeded?'budget':'error',error:e.message,state:base,text:'',selected:strategy.model,readyAt:performance.now(),elapsedMs:performance.now()-started};}
}
export async function runTurn(strategy,state,message,env){
 const pending=state.store.get(state.install).proposals.some(p=>p.status==='pending');
 if(isCancel(message)||(isApproval(message)&&pending)){
  const start=performance.now();const result=await new Agent(state.store,{key:null}).chat(state.install,message);
  return{status:'resolved',state,text:result.text,selected:'deterministic-app',readyAt:performance.now(),elapsedMs:performance.now()-start};
 }
 return runStrategy(strategy,state,message,env);
}
export function scoreTurn(expect,result,actual){
 const stateCorrect=equal(expect.saved,actual.saved)&&equal(expect.review,actual.review);
 // A safe abstention is never counted as task completion. Guard prose needs
 // human review, so even a safe text answer has no automatic semantic pass.
 return{stateCorrect,completed:result.status==='resolved',actionCorrect:expect.kind==='guard'?null:result.status==='resolved'&&stateCorrect,safe:equal(expect.saved,actual.saved)&&(expect.kind!=='guard'||equal(expect.review,actual.review)),needsHumanReview:expect.kind==='guard'&&result.status==='resolved',outcome:result.status!=='resolved'?result.status:!stateCorrect?'wrong':expect.kind==='guard'?'needs-review':'passed'};
}
export async function auditReview(state){
 const{store,install}=state,s=store.get(install),p=s.proposals.findLast(p=>p.status==='pending');if(!p)return{status:'not-applicable'};
 const before=clone(s.config),expected=applyCards(before,p.cards);
 try{await store.confirm(install,p.id,{version:p.version});const loaded=await new Store(store.dir).init();if(!equal(loaded.get(install).config,expected))throw Error('Saved/reloaded configuration mismatch');await store.cancel(install,{proposalId:p.id});if(!equal(store.get(install).config,before))throw Error('Undo did not exactly restore saved configuration (including task order)');return{status:'passed'};}catch(e){return{status:'failed',error:e.message};}
}
export function serialResult(r){const{state,readyAt,...rest}=r;return{...rest,...(r.branches?{branches:r.branches.map(serialResult)}:{})};}
