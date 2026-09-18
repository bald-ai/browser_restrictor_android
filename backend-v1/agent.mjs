import {fitChatContext} from './context-budget.mjs';
import {readAppDocumentation} from './app-docs.mjs';
import {captureBase} from './edits.mjs';
import {fail,id,clone,applyCards,isCancel,isApproval,isExplicitCancellation} from './domain.mjs';
import {astraPrompt,tools} from './prompts.mjs';
export function contextFor(store,install){const s=store.get(install),p=s.proposals.findLast(p=>p.status==='pending'),visible=p||s.proposals.at(-1);return{...store.envelope(s),selectedCardId:s.selectedCardId,selectedCard:visible?.cards.find(c=>c.id===s.selectedCardId)||null,lastReview:p?null:clone(visible||null),proposal:p?clone(p):null,proposedConfig:p?applyCards(s.config,p.cards):null,statistics:clone({summary:s.statistics.summary,events:s.statistics.events?.slice(-15),updatedAt:s.statistics.updatedAt}),recentChanges:clone(s.changes.slice(-3))};}
export class Agent {
 constructor(store,{key=process.env.OPENAI_API_KEY,fetcher=fetch}={}){this.store=store;this.key=key;this.fetcher=fetcher;}
 async chat(install,message,{source='text'}={}){
  fail(typeof message==='string'&&message.trim()&&message.length<=5000,'Write a message of 1–5,000 characters.');const store=this.store,runtime=store.getRuntime(install);
  if(isCancel(message)){await store.message(install,'user',message,source);const result=await store.cancel(install);const text=result.restored?'Undone. The previous values are saved; your phone will confirm when they are active.':'Cancelled. Those proposed changes will not be applied.';await store.message(install,'assistant',text);return{text,...store.snapshot(install)};}
  const pending=clone(store.get(install).proposals.findLast(p=>p.status==='pending')||null);
  if(isApproval(message)&&pending){await store.message(install,'user',message,source);const result=await store.confirm(install,pending.id,{version:pending.version});const text='Saved and checked. Waiting for your phone to apply the changes.';await store.message(install,'assistant',text);return{text,...result};}
  fail(!runtime.turn,'I’m finishing your previous request. You can still cancel it or approve the displayed cards.',409);fail(this.key,'Conversation is not configured yet. Manual controls are available.',503);
  const ctx={id:id(),cancelled:false,approvalTarget:pending?{id:pending.id,version:pending.version}:null};runtime.turn=ctx;ctx.editBase=captureBase(store,install);
  let text='',input=[];const started=Date.now(),trace=[];
  try{
   await store.message(install,'user',message,source);store.notify(install,'working',{text:'Preparing your request'});
   input=[...store.get(install).messages.map(m=>({role:m.role,content:m.text})),{role:'developer',content:'Fresh CURRENT CONTEXT (app data): '+JSON.stringify(contextFor(store,install))}];
   for(let step=0;step<5;step++){
    fail(!ctx.cancelled,'Request cancelled.',409);
    const body=fitChatContext({model:'gpt-6-astra',service_tier:'fast',reasoning:{effort:'low'},text:{verbosity:'low'},instructions:astraPrompt,input,tools,parallel_tool_calls:false,max_output_tokens:2400,store:false});
    input=body.input;
    // UTF-8 bytes upper-bound input tokens; reserve maximum output and cache-write premium.
    const reservation=await store.reserve(install,'astra',Buffer.byteLength(JSON.stringify(body))*.000025+.24);
    let settled=false,r;
    try{const response=await this.fetcher('https://api.openai.com/v1/responses',{method:'POST',headers:{Authorization:`Bearer ${this.key}`,'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(60000)});r=await response.json();const usage=r.usage;const inputTokens=usage?.input_tokens||0,cached=usage?.input_tokens_details?.cached_tokens||0,writes=usage?.input_tokens_details?.cache_write_tokens;
     const usd=usage?((inputTokens-cached)*20+cached*2+(usage.output_tokens||0)*100+(Number.isFinite(writes)?writes:inputTokens-cached)*5)/1e6:response.status>=400&&response.status<500?0:null;
     await store.settle(reservation,usd,{model:'gpt-6-astra',serviceTier:r.service_tier,usage:usage||null,responseId:r.id||null,estimated:true,costBasis:'published-rates-not-invoice'});settled=true;fail(response.ok,`The assistant could not respond (${response.status}). Please try again.`,502);fail(r.status==='completed','The assistant response was incomplete. Please try again.',502);
    }finally{if(!settled)await store.settle(reservation,null,{reason:'Provider outcome unknown; reservation retained conservatively.'});}
    if(ctx.cancelled){text='Request cancelled.';break;}
    const calls=(r.output||[]).filter(x=>x.type==='function_call');if(!calls.length){text=(r.output||[]).filter(x=>x.type==='message').flatMap(x=>x.content||[]).map(x=>x.text||'').join('\n');break;}
    input.push(...(r.output||[]));
    for(const call of calls){
     fail(!ctx.cancelled,'Request cancelled.',409);let result,args;try{args=JSON.parse(call.arguments);trace.push({name:call.name,at:Date.now()});
      if(call.name==='read_setup')result=contextFor(store,install);
      else if(call.name==='read_details')result=args.topic==='history'?store.get(install).statistics:readAppDocumentation(args.topic);
      else if(call.name==='propose_changes'){fail(Array.isArray(args.changes)&&!Object.hasOwn(args,'config'),'Provide explicit edits, not a replacement configuration.');result=await store.stage(install,args,ctx);text=`${result.title}. Review ${result.cards.length===1?'the change':`all ${result.cards.length} changes`} above, then tap Apply or tell me to apply them.`;}
      else if(call.name==='confirm_changes'){fail(ctx.approvalTarget?.id===args.proposalId&&ctx.approvalTarget?.version===args.version,'This approval is for a different review. Please review the latest cards.',409);fail(typeof args.approvalQuote==='string'&&message.toLowerCase().includes(args.approvalQuote.toLowerCase())&&isApproval(message)&&isApproval(args.approvalQuote)&&!/(?:do not|don't|never|not yet|without|before)\b/i.test(message),'I need your explicit approval of the displayed review.',409);result=await store.confirm(install,args.proposalId,{version:args.version},ctx);text='Saved and checked. Waiting for your phone to apply the changes.';}
      else if(call.name==='cancel_changes'){fail(typeof args.requestQuote==='string'&&message.toLowerCase().includes(args.requestQuote.toLowerCase())&&isExplicitCancellation(message)&&isExplicitCancellation(args.requestQuote)&&!/(?:do not|don.t|never)\s+(?:cancel|undo|revert)/i.test(message),'Please explicitly ask to cancel or undo.',409);result=await store.cancel(install,args);text=result.restored?'Undone. Previous values saved; waiting for the phone.':'Cancelled. The proposal will not be applied.';ctx.cancellationResult=text;}
      else throw Error('Unsupported app function.');
      result={ok:true,result};
     }catch(e){result={ok:false,error:e.message};}
     input.push({type:'function_call_output',call_id:call.call_id,output:JSON.stringify(result)});
    }
    if(text)break; // Predictable save/proposal verification happens in app code, avoiding a second reasoning round.
   }
   if(ctx.cancelled)text=ctx.cancellationResult||'Request cancelled.';fail(text,'The assistant did not finish this request. Try a shorter request.',502);
   if(!ctx.cancelled)await store.message(install,'assistant',text);
   return{text,cancelled:ctx.cancelled,elapsedMs:Date.now()-started,trace,...store.snapshot(install)};
  }catch(e){if(ctx.cancelled)return{text:'Request cancelled.',cancelled:true,...store.snapshot(install)};await store.message(install,'assistant',e.message);throw e;}
  finally{if(runtime.turn===ctx)runtime.turn=null;store.notify(install,'done',{text,elapsedMs:Date.now()-started,trace});}
 }
}
