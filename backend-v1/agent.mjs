import {assistantSettingsFor,responseOptions,estimatedModelCost,reserveModelCost} from './assistant-settings.mjs';
import {fitChatContext} from './context-budget.mjs';
import {readAppDocumentation} from './app-docs.mjs';
import {captureBase,expandChanges} from './edits.mjs';
import {buildQuestions,decodeChanges,createJevEvaluator,jevHandoffPrompt} from './jev.mjs';
import {fail,id,clone,applyCards,isCancel,isApproval,isExplicitCancellation} from './domain.mjs';
import {astraPrompt,tools} from './prompts.mjs';
export function contextFor(store,install){const s=store.get(install),p=s.proposals.findLast(p=>p.status==='pending'),visible=p||s.proposals.at(-1);return{...store.envelope(s),assistantSettings:assistantSettingsFor(s),selectedCardId:s.selectedCardId,selectedCard:visible?.cards.find(c=>c.id===s.selectedCardId)||null,lastReview:p?null:clone(visible||null),proposal:p?clone(p):null,proposedConfig:p?applyCards(s.config,p.cards):null,statistics:clone({summary:s.statistics.summary,events:s.statistics.events?.slice(-15),updatedAt:s.statistics.updatedAt}),recentChanges:clone(s.changes.slice(-3))};}
function validateMessage(message){fail(typeof message==='string'&&message.trim()&&message.length<=5000,'Write a message of 1–5,000 characters.');}
function reviewInstructions(review){return `${review.title}. Review ${review.cards.length===1?'the change':`all ${review.cards.length} changes`} above, then tap Apply or tell me to apply them.`;}
export class Agent {
 constructor(store,{key=process.env.OPENAI_API_KEY,fetcher=fetch,jevKey=process.env.AI_GATEWAY_API_KEY,jevEvaluator=null,jevTimeoutMs=3000}={}){this.store=store;this.key=key;this.fetcher=fetcher;this.jevEvaluator=jevEvaluator||(jevKey?createJevEvaluator(jevKey):null);this.jevTimeoutMs=jevTimeoutMs;}
 async chat(install,message,{source='text',jevFallbackReason='unavailable'}={}){
  validateMessage(message);const store=this.store,runtime=store.getRuntime(install);
  const pending=clone(store.get(install).proposals.findLast(p=>p.status==='pending')||null);
  const direct=await this.directChat(install,message,source,pending);if(direct)return direct;
  fail(!runtime.turn,'I’m finishing your previous request. You can still cancel it or approve the displayed cards.',409);fail(this.key,'Conversation is not configured yet. Manual controls are available.',503);
  const ctx={id:id(),cancelled:false,approvalTarget:pending?{id:pending.id,version:pending.version}:null};runtime.turn=ctx;ctx.editBase=captureBase(store,install);
  const assistantSettings=assistantSettingsFor(store.get(install));
  let text='';const started=Date.now(),trace=[];
  try{
   await store.message(install,'user',message,source);store.notify(install,'working',{text:'Preparing your request'});
   const routed=await this.tryJev(install,message,ctx,trace,jevFallbackReason);
   text=routed.text;jevFallbackReason=routed.fallbackReason;
   if(!text)text=await this.modelChat(install,message,ctx,assistantSettings,jevFallbackReason,trace);
   const result=await this.finishChat(install,ctx,text,started,trace);text=result.text;return result;
  }catch(e){if(ctx.cancelled)return{text:'Request cancelled.',cancelled:true,...store.snapshot(install)};await store.message(install,'assistant',e.message);throw e;}
  finally{if(runtime.turn===ctx)runtime.turn=null;store.notify(install,'done',{text,elapsedMs:Date.now()-started,trace});}
 }
 async finishChat(install,ctx,text,started,trace){
  const store=this.store;
   if(ctx.cancelled)text=ctx.cancellationResult||'Request cancelled.';fail(text,'The assistant did not finish this request. Try a shorter request.',502);
   if(!ctx.cancelled)await store.message(install,'assistant',text);
   return{text,cancelled:ctx.cancelled,elapsedMs:Date.now()-started,trace,...store.snapshot(install)};
 }
 async modelChat(install,message,ctx,assistantSettings,jevFallbackReason,trace){
  const store=this.store;let input=[],text='';
   input=[...store.get(install).messages.map(m=>({role:m.role,content:m.text})),{role:'developer',content:'Fresh CURRENT CONTEXT (app data): '+JSON.stringify(contextFor(store,install))}];
   for(let step=0;!text&&step<5;step++){
    fail(!ctx.cancelled,'Request cancelled.',409);
    const body=fitChatContext({...responseOptions(assistantSettings),text:{verbosity:'low'},instructions:astraPrompt+jevHandoffPrompt(jevFallbackReason),input,tools,parallel_tool_calls:false,store:false});
    input=body.input;
    const r=await this.requestResponse(install,assistantSettings,body);
    if(ctx.cancelled){text='Request cancelled.';break;}
    const output=r.output||[],calls=output.filter(x=>x.type==='function_call');
    if(!calls.length){text=output.filter(x=>x.type==='message').flatMap(x=>x.content||[]).map(x=>x.text||'').join('\n');break;}
    input.push(...output);
    text=await this.executeCalls(install,message,ctx,calls,input,trace);
    if(text)break; // Predictable save/proposal verification happens in app code, avoiding a second reasoning round.
   }
  return text;
 }
 async executeCalls(install,message,ctx,calls,input,trace){
  let text='';
    for(const call of calls){
     fail(!ctx.cancelled,'Request cancelled.',409);let result,args;try{args=JSON.parse(call.arguments);trace.push({name:call.name,at:Date.now()});
      const outcome=await this.executeFunction(install,message,ctx,call.name,args);
      result=outcome.result;if(outcome.text)text=outcome.text;
      result={ok:true,result};
     }catch(e){result={ok:false,error:e.message};}
     input.push({type:'function_call_output',call_id:call.call_id,output:JSON.stringify(result)});
    }
  return text;
 }
 async tryJev(install,message,ctx,trace,jevFallbackReason){
  const store=this.store;let text='';
   if(this.jevEvaluator&&!isApproval(message)&&!isExplicitCancellation(message)){
    jevFallbackReason='unresolved';
    const questions=buildQuestions(ctx.editBase.config,message);
    const state={latestRequest:message,recentConversation:store.get(install).messages.slice(-9,-1).map(({role,text})=>({role,text})),context:contextFor(store,install)};
    const reservation=await store.reserve(install,'jev',Buffer.byteLength(JSON.stringify({state,questions}))*.04/1e6);
    const evaluationStarted=Date.now();
    const evaluation=await this.evaluateJev(install,state,questions,reservation,trace);
    const result=evaluation.result;if(evaluation.failed)jevFallbackReason='provider_error';
    fail(!ctx.cancelled,'Request cancelled.',409);
    const changes=this.acceptedJevChanges(install,result,questions,ctx);
    trace.push({name:'jev',accepted:Boolean(changes),elapsedMs:Date.now()-evaluationStarted,at:Date.now()});
    if(changes){
     // Use the same explicit-edit transaction as Astra: validates the captured
     // revision/review, preserves pending edits and never saves without approval.
     const review=await store.stage(install,{changes,expectedRevision:ctx.editBase.revision,title:'Requested changes'},ctx);
     text=reviewInstructions(review);
     trace.push({name:'propose_changes',at:Date.now()});
    }
   }
  return {text,fallbackReason:jevFallbackReason};
 }
 acceptedJevChanges(install,result,questions,ctx){
  const store=this.store;
    let changes=null;
    try{changes=result?decodeChanges(result.answers||{},questions,ctx.editBase.config):null;if(changes&&JSON.stringify(expandChanges(ctx.editBase.config,changes))===JSON.stringify(store.get(install).config))changes=null;}catch{changes=null;}
  return changes;
 }
 async evaluateJev(install,state,questions,reservation,trace){
  const store=this.store;let failed=false;
    let result=null,settled=false;
    try{
     result=await this.jevEvaluator({state,questions,abortSignal:AbortSignal.timeout(this.jevTimeoutMs)});
     const tokens=result.usage?.inputTokens;
     await store.settle(reservation,Number.isFinite(tokens)?tokens*.04/1e6:null,{model:'typesafe-ai/jev',usage:result.usage||null,estimated:true,costBasis:'published-rates-not-invoice'});settled=true;
    }catch(e){failed=true;trace.push({name:'jev_fallback',reason:'provider_error',at:Date.now()});}
    finally{if(!settled)await store.settle(reservation,null,{model:'typesafe-ai/jev',reason:'Provider outcome unknown; reservation retained conservatively.'});}
  return {result,failed};
 }
 async directChat(install,message,source,pending){
  const store=this.store;
  if(isCancel(message)){await store.message(install,'user',message,source);const result=await store.cancel(install);const text=result.restored?'Undone. The previous values are saved; your phone will confirm when they are active.':'Cancelled. Those proposed changes will not be applied.';await store.message(install,'assistant',text);return{text,...store.snapshot(install)};}
  if(isApproval(message)&&pending){await store.message(install,'user',message,source);const result=await store.confirm(install,pending.id,{version:pending.version});const text='Saved and checked. Waiting for your phone to apply the changes.';await store.message(install,'assistant',text);return{text,...result};}
  return null;
 }
 async requestResponse(install,assistantSettings,body){
    const store=this.store;
    const reservation=await store.reserve(install,assistantSettings.model==='gpt-6-astra'?'astra':'assistant',reserveModelCost(assistantSettings,body));
    let settled=false,r;
    try{const response=await this.fetcher('https://api.openai.com/v1/responses',{method:'POST',headers:{Authorization:`Bearer ${this.key}`,'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(['none','low'].includes(assistantSettings.reasoning)?60000:180000)});r=await response.json();await this.settleResponse(reservation,assistantSettings,response,r);settled=true;fail(response.ok,`The assistant could not respond (${response.status}). Please try again.`,502);fail(r.status==='completed','The assistant response was incomplete. Please try again.',502);
    }finally{if(!settled)await store.settle(reservation,null,{reason:'Provider outcome unknown; reservation retained conservatively.'});}
    return r;
 }
 async settleResponse(reservation,assistantSettings,response,r){
  const store=this.store;const usage=r.usage;
     const usd=usage?estimatedModelCost(assistantSettings,usage,r.service_tier||assistantSettings.serviceTier):response.status>=400&&response.status<500?0:null;
     await store.settle(reservation,usd,{model:assistantSettings.model,reasoning:assistantSettings.reasoning,serviceTier:r.service_tier||assistantSettings.serviceTier,usage:usage||null,responseId:r.id||null,estimated:true,costBasis:'published-rates-not-invoice'});
 }
 async executeFunction(install,message,ctx,name,args){
  switch(name){
   case 'read_setup':return {result:contextFor(this.store,install),text:''};
   case 'read_details':return {result:args.topic==='history'?this.store.get(install).statistics:readAppDocumentation(args.topic),text:''};
   case 'propose_changes':return this.propose(install,ctx,args);
   case 'confirm_changes':return this.confirm(install,message,ctx,args);
   case 'cancel_changes':return this.cancel(install,message,ctx,args);
   default:throw Error('Unsupported app function.');
  }
 }
 async propose(install,ctx,args){
  fail(Array.isArray(args.changes)&&!Object.hasOwn(args,'config'),'Provide explicit edits, not a replacement configuration.');
  const result=await this.store.stage(install,args,ctx);
  return {result,text:reviewInstructions(result)};
 }
 async confirm(install,message,ctx,args){
  fail(ctx.approvalTarget?.id===args.proposalId&&ctx.approvalTarget?.version===args.version,'This approval is for a different review. Please review the latest cards.',409);
  fail(typeof args.approvalQuote==='string'&&message.toLowerCase().includes(args.approvalQuote.toLowerCase())&&isApproval(message)&&isApproval(args.approvalQuote)&&!/(?:do not|don't|never|not yet|without|before)\b/i.test(message),'I need your explicit approval of the displayed review.',409);
  const result=await this.store.confirm(install,args.proposalId,{version:args.version},ctx);
  return {result,text:'Saved and checked. Waiting for your phone to apply the changes.'};
 }
 async cancel(install,message,ctx,args){
  fail(typeof args.requestQuote==='string'&&message.toLowerCase().includes(args.requestQuote.toLowerCase())&&isExplicitCancellation(message)&&isExplicitCancellation(args.requestQuote)&&!/(?:do not|don.t|never)\s+(?:cancel|undo|revert)/i.test(message),'Please explicitly ask to cancel or undo.',409);
  const result=await this.store.cancel(install,args),text=result.restored?'Undone. Previous values saved; waiting for the phone.':'Cancelled. The proposal will not be applied.';
  ctx.cancellationResult=text;return {result,text};
 }
}
