import http from 'node:http';
import {isCancellationRequest} from './cancellation.mjs';
import {EventEmitter} from 'node:events';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import WebSocket,{WebSocketServer} from 'ws';
import {seed,statistics,estimate} from './domain.mjs';
import {functionTools,instructions,indexDocs,makeFunctionRunner} from './functions.mjs';
const root=path.dirname(fileURLToPath(import.meta.url)),port=Number(process.env.PORT||4317),budget=Number(process.env.EXPERIMENT_BUDGET_USD||3);
const key=process.env.OPENAI_API_KEY;if(!key)throw Error('OPENAI_API_KEY is required.');
const resultDir=path.join(root,'results','functions-v2');await fs.mkdir(resultDir,{recursive:true});
const stateFile=path.join(root,'state-v2.json');let state;
try{state=JSON.parse(await fs.readFile(stateFile,'utf8'));}catch{state={experiment:'functions-v2',config:seed(),revision:0,conversations:[],turns:[],voices:[],events:[],drafts:[],changes:[]};}
for(const t of state.turns){if(t.calls?.length){t.usage=sumUsage(t.calls);t.cost=estimateCalls(t.calls,t.model);}}
const sections=indexDocs(await fs.readFile(path.resolve(root,'../../Dev/REBUILD_PLAN_V1.md'),'utf8'));
let active=state.conversations.at(-1)||null,busy=false,voiceActive=false,saveQueue=Promise.resolve();const subscribers=new Set();let currentTurn=null,cancellationPromise=null;
const uuid=()=>crypto.randomUUID(),sleep=ms=>new Promise(r=>setTimeout(r,ms));
const save=()=>{const content=JSON.stringify(state,null,2);saveQueue=saveQueue.then(()=>fs.writeFile(stateFile+'.tmp',content).then(()=>fs.rename(stateFile+'.tmp',stateFile)));return saveQueue;};
await save();
const totals=()=>({model:state.turns.reduce((n,t)=>n+(t.cost?.usd||0),0),modelWithCacheWriteAllowance:state.turns.reduce((n,t)=>n+(t.cost?.withCacheWriteAllowance||0),0),voice:state.voices.reduce((n,v)=>n+v.seconds*.05/60,0),turns:state.turns.length,unpricedTurns:state.turns.filter(t=>t.missingUsage).length});
const snapshot=()=>({experiment:state.experiment,voice:'ripple',agentServiceTier:'fast',defaultModel:'gpt-6-astra',config:state.config,revision:state.revision,statistics,conversations:state.conversations.map(({previousResponseId,...c})=>c),turns:state.turns,voices:state.voices,events:state.events.slice(-100),drafts:state.drafts.filter(d=>d.conversationId===active?.id&&d.status!=='superseded').slice(-3),changes:state.changes.map(({before,expected,...c})=>c),totals:totals(),budget,busy,cancelling:Boolean(currentTurn?.cancelled),voiceActive,activeConversation:active?.id||null});
function send(ws,data){if(ws.readyState===1)ws.send(JSON.stringify(data));}
function broadcast(data){for(const s of subscribers)send(s,data);}
function log(kind,title,detail='',extra={}){const e={id:uuid(),time:Date.now(),kind,title,detail,...extra};state.events.push(e);if(state.events.length>1000)state.events.shift();broadcast({type:'event',event:e});return e;}
function changed(){broadcast({type:'state',state:snapshot()});save();}
function checkBudget(){const t=totals();if(t.modelWithCacheWriteAllowance+t.voice>=budget||state.turns.length>=40)throw Error('Experiment limit reached. Review measured costs before running more.');}
function newConversation({model='gpt-6-astra',label='New conversation'}={}){
 if(busy||voiceActive)throw Error('Finish the active interaction first.');if(!['gpt-5.6-terra','gpt-5.6-luna','gpt-6-astra','gpt-5.6-sol'].includes(model))throw Error('Model not enabled in this experiment.');
 active={id:uuid(),model,label,createdAt:Date.now(),messages:[],previousResponseId:null};state.conversations.push(active);changed();return active;
}
const execute=makeFunctionRunner({state:()=>state,sections,save,readPersisted:async()=>{await saveQueue;return JSON.parse(await fs.readFile(stateFile,'utf8'));},emit:(...args)=>{log(...args);changed();}});
async function cancelRequest(message='Cancel that',{draftId=null,source='text'}={}){
 if(cancellationPromise)return cancellationPromise;
 const c=active;if(!c)throw Error('No active conversation to cancel.');
 const inflight=currentTurn;
 const draft=draftId?state.drafts.find(d=>d.id===draftId&&d.conversationId===c.id):null;
 if(draftId&&!draft)throw Error('Review card not found.');
 const interrupts=inflight&&(!draftId||inflight.approvedDraftId===draftId||inflight.targetDraftId===draftId||draft?.createdTurn===inflight.turnId);
 if(interrupts){inflight.cancelled=true;inflight.cancellationText='Request cancelled.';}
 const targetDraft=draftId||(interrupts?(inflight.targetDraftId||inflight.approvedDraftId||state.drafts.findLast(d=>d.conversationId===c.id&&d.status==='pending')?.id):null);
 const ctx={turnId:uuid(),conversationId:c.id,userMessage:message,cancelRequested:true,targetTurnId:interrupts?inflight.turnId:null,readRevision:null,pending:[],verified:[]};
 c.messages.push({role:'user',text:message,source,time:Date.now()});log('cancelled','Cancellation requested',message);changed();
 cancellationPromise=(async()=>{
  const result=await execute('cancel_change',{draftId:targetDraft||null,changeId:null,requestQuote:message},ctx);
  if(result.changeId){const check=await execute('verify_change',{changeId:result.changeId},ctx);if(!check.verified)throw Error('Undo was saved but read-back did not match.');result.verified=true;result.text=result.alreadyCancelled?'This change was already undone. I checked the saved values.':'Cancelled. The previous values are restored and verified.';}
  else result.text=result.summary;
  if(interrupts)inflight.cancellationText=result.text;
  c.messages.push({role:'assistant',text:result.text,time:Date.now()});c.previousResponseId=null;log('cancelled',result.text,JSON.stringify(result));await save();changed();
  for(const listener of cancellationListeners)listener(result.text);
  return {...result,config:state.config};
 })();
 try{return await cancellationPromise;}finally{cancellationPromise=null;}
}
const cancellationListeners=new Set();
async function response(body,timeout){
 const started=Date.now();const r=await fetch('https://api.openai.com/v1/responses',{method:'POST',headers:{Authorization:`Bearer ${key}`,'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(timeout)});
 const j=await r.json();if(!r.ok)throw Error(`OpenAI ${r.status}: ${j.error?.message||'Request failed'}`);return {data:j,elapsedMs:Date.now()-started};
}
function sumUsage(calls){
 const valid=calls.filter(c=>c.usage);if(!valid.length)return null;
 const allWrites=valid.every(c=>Number.isFinite(c.usage.input_tokens_details?.cache_write_tokens));
 return valid.reduce((a,c)=>{if(allWrites)a.input_tokens_details.cache_write_tokens+=c.usage.input_tokens_details.cache_write_tokens;return ((a,c)=>{a.input_tokens+=c.usage.input_tokens||0;a.input_tokens_details.cached_tokens+=c.usage.input_tokens_details?.cached_tokens||0;a.output_tokens+=c.usage.output_tokens||0;a.output_tokens_details.reasoning_tokens+=c.usage.output_tokens_details?.reasoning_tokens||0;return a;})(a,c);},{input_tokens:0,input_tokens_details:{cached_tokens:0,...(allWrites?{cache_write_tokens:0}:{})},output_tokens:0,output_tokens_details:{reasoning_tokens:0}});
}
function estimateCalls(calls,model){
 const total=estimate(sumUsage(calls),model);if(!total)return null;
 const costs=calls.map(c=>estimate(c.usage,model,c.serviceTier)).filter(Boolean);
 return {...total,usd:costs.reduce((n,c)=>n+c.usd,0),withCacheWriteAllowance:costs.reduce((n,c)=>n+c.withCacheWriteAllowance,0)};
}
async function runTurn(message,{selectedTask=null,source='text',approvedDraftId=null}={}){
 if(busy)throw Error('Another request is running.');if(typeof message!=='string'||!message.trim()||message.length>5000)throw Error('Message must be 1–5,000 characters.');checkBudget();
 if(!active){if(voiceActive){active={id:uuid(),model:'gpt-6-astra',label:'Voice conversation',createdAt:Date.now(),messages:[],previousResponseId:null};state.conversations.push(active);}else newConversation();}
 busy=true;const c=active,id=uuid(),started=Date.now(),calls=[],toolTrace=[],executed=new Map();
 const ctx={turnId:id,conversationId:c.id,userMessage:message,selectedTask,approvedDraftId,readRevision:null,docsRead:false,pending:[],verified:[],cancelled:false};currentTurn=ctx;
 const checkCancelled=()=>{if(ctx.cancelled)throw Error('Request cancelled.');};
 c.messages.push({role:'user',text:message,source,time:started});log('user',message,source);changed();
 let input=[{role:'user',content:message+'\n[UI context: selectedTask='+JSON.stringify(selectedTask)+'. Use read_setup for current state. Pending drafts: '+JSON.stringify(state.drafts.filter(d=>d.conversationId===c.id&&d.status==='pending').map(d=>({draftId:d.id,summary:d.summary})))+']'}],result='',failure=null;
 try{
  if(approvedDraftId){
   const d=state.drafts.find(d=>d.id===approvedDraftId&&d.conversationId===c.id);if(!d)throw Error('Review card no longer exists.');
   for(const [name,args] of [['read_setup',{}],['confirm_change',{expectedRevision:state.revision,draftId:d.id,approvalQuote:message}]]){
    const t=Date.now();log('tool',name,JSON.stringify({...args,trigger:'Confirm change button'},null,2));const output={ok:true,...await execute(name,args,ctx)};toolTrace.push({name,args,output,elapsedMs:Date.now()-t,origin:'ui'});
    input.push({role:'developer',content:'The user pressed Confirm change. The app already ran '+name+' and returned: '+JSON.stringify(output)+'. Do not repeat that operation. Call verify_change for the returned changeId and report the checked result.'});
   }
  }
  for(let step=0;step<9;step++){
   checkCancelled();checkBudget();const remaining=90000-(Date.now()-started);if(remaining<1000)throw Error('This request exceeded the 90-second experiment deadline.');
   const request={model:c.model,...(['gpt-6-astra','gpt-5.6-sol'].includes(c.model)?{service_tier:'fast'}:{}),instructions,reasoning:{effort:'low'},text:{verbosity:'low'},input,tools:ctx.pending.length?functionTools.filter(t=>t.name==='verify_change'):functionTools,parallel_tool_calls:true,max_output_tokens:1600,store:true};
   if(c.previousResponseId)request.previous_response_id=c.previousResponseId;if(ctx.pending.length)request.tool_choice={type:'function',name:'verify_change'};
   const {data:r,elapsedMs}=await response(request,Math.min(remaining,45000));
   c.previousResponseId=r.id;calls.push({responseId:r.id,elapsedMs,usage:r.usage||null,status:r.status,serviceTier:r.service_tier,requestedServiceTier:request.service_tier||'auto'});await fs.writeFile(path.join(resultDir,`${id}-${step}.json`),JSON.stringify(r,null,2));
   checkCancelled();
   if(r.status!=='completed')throw Error(`Model response ${r.status}: ${r.incomplete_details?.reason||r.error?.message||'unknown'}`);
   const fc=(r.output||[]).filter(x=>x.type==='function_call');
   if(!fc.length){result=(r.output||[]).filter(x=>x.type==='message').flatMap(x=>x.content||[]).map(x=>x.text||'').join('\n');if(ctx.pending.length)throw Error('Saved change has not been verified.');if(!result)throw Error('Model returned no text or function calls.');break;}
   input=[];
   for(const f of fc){
    checkCancelled();if(f.name==='confirm_change'){try{ctx.targetDraftId=JSON.parse(f.arguments).draftId;}catch{}}
    if(toolTrace.length>=18)throw Error('Function-call limit reached.');let output=executed.get(f.call_id);
    if(!output){const toolStarted=Date.now();let args;try{args=JSON.parse(f.arguments);log('tool',f.name,JSON.stringify(args,null,2));const value=await execute(f.name,args,ctx);output={ok:true,...value};}catch(e){output={ok:false,error:e.message};log('error','Function rejected the request',f.name+': '+e.message);}
     executed.set(f.call_id,output);toolTrace.push({name:f.name,args,output,elapsedMs:Date.now()-toolStarted});changed();}
    input.push({type:'function_call_output',call_id:f.call_id,output:JSON.stringify(output)});
   }
  }
  checkCancelled();if(!result)throw Error('Conversation exceeded the function-step limit.');
  c.messages.push({role:'assistant',text:result,time:Date.now()});log('done',result,`${((Date.now()-started)/1000).toFixed(1)} seconds`);
 }catch(e){if(ctx.cancelled){result=ctx.cancellationText||'Request cancelled.';c.previousResponseId=null;}else{failure=e.message;result='The request could not finish: '+failure+(ctx.pending.length?' A change was saved, but its read-back is incomplete. Check the current card before retrying.':'');c.messages.push({role:'assistant',text:result,time:Date.now()});log('error','Request did not finish',result);}}
 finally{
  const usage=sumUsage(calls);const turn={id,conversationId:c.id,model:c.model,reasoning:'low',message,source,status:ctx.cancelled?'cancelled':failure?'failed':'completed',error:failure,elapsedMs:Date.now()-started,calls,usage,cost:estimateCalls(calls,c.model),missingUsage:!calls.length||calls.some(c=>!c.usage),toolCalls:toolTrace.length,toolTrace,verifiedChanges:ctx.verified,completedAt:Date.now()};state.turns.push(turn);currentTurn=null;busy=false;changed();await save();
 }
 if(failure)throw Error(result);return {text:result,cancelled:ctx.cancelled,turn:state.turns.at(-1),config:state.config};
}
const server=http.createServer(async(req,res)=>{
 const url=new URL(req.url,`http://127.0.0.1:${port}`),hosts=[`localhost:${port}`,`127.0.0.1:${port}`];const reply=(code,data)=>{res.writeHead(code,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(data));};
 if(!hosts.includes(req.headers.host)||req.headers.origin&&!hosts.some(h=>req.headers.origin===`http://${h}`)){reply(403,{error:'Local requests only.'});return;}
 try{
  if(req.method==='GET'&&url.pathname==='/api/state'){reply(200,snapshot());return;}
  if(req.method==='GET'&&url.pathname==='/api/report'){reply(200,{...snapshot(),costNote:'Functions experiment only. Reported model usage plus GPT-Live seconds. No hosted sandbox charges. Cache-write allowance is an estimate, not an invoice.'});return;}
  if(req.method==='GET'&&url.pathname==='/api/docs'){reply(200,{sections});return;}
  if(req.method==='POST'){
   let raw='';for await(const chunk of req){raw+=chunk;if(raw.length>65536)throw Error('Request too large.');}const body=raw?JSON.parse(raw):{};
   if(url.pathname==='/api/voice/session'){
    checkBudget();if(busy||voiceActive)throw Error('Finish the active interaction first.');
    if(typeof body.sdp!=='string'||!body.sdp.startsWith('v=0'))throw Error('An SDP offer is required.');
    voiceActive=true;changed();let created;
    try{
     const r=await fetch('https://api.openai.com/v1/live/sessions',{method:'POST',headers:{Authorization:`Bearer ${key}`,'Content-Type':'application/json'},body:JSON.stringify({session:{model:'gpt-live-1',instructions:liveInstructions,audio:{output:{voice:'ripple'}},delegation:{type:'client'}},transport:{type:'webrtc',sdp:body.sdp}}),signal:AbortSignal.timeout(20000)});
     created=await r.json();if(!r.ok)throw Error(created.error?.message||'Live session creation failed.');
     const client=new EventEmitter();client.readyState=1;client.send=()=>{};client.close=()=>{if(client.readyState===3)return;client.readyState=3;client.emit('close');};
     rtcSessions.set(created.session.id,client);
     await handleVoice(client,{url:'/voice',headers:{}},{sessionId:created.session.id,selectedTask:body.selectedTask});
     reply(201,created);
    }catch(e){if(created?.session?.id){await fetch(`https://api.openai.com/v1/live/sessions/${encodeURIComponent(created.session.id)}/hangup`,{method:'POST',headers:{Authorization:`Bearer ${key}`}}).catch(()=>{});rtcSessions.delete(created.session.id);}voiceActive=false;changed();throw e;}return;
   }
   if(url.pathname==='/api/voice/stop'){const client=rtcSessions.get(body.sessionId);if(client)client.emit('message',JSON.stringify({type:'session.close'}));reply(200,{ok:true});return;}
   if(url.pathname==='/api/cancellation-result'){await fs.mkdir(path.join(root,'results','cancellation'),{recursive:true});await fs.writeFile(path.join(root,'results','cancellation','voice-evidence.json'),JSON.stringify(body,null,2));reply(200,{ok:true});return;}
   if(url.pathname==='/api/transport-result'){if(!['websocket','webrtc'].includes(body.transport))throw Error('Unknown transport');await fs.writeFile(path.join(root,'results','transport-check',body.transport+(body.model==='gpt-5.6-sol'?'-sol':'')+(body.serviceTier==='fast'?'-fast':'')+'.json'),JSON.stringify(body,null,2));reply(200,{ok:true});return;}
   if(url.pathname==='/api/cancel'){reply(200,await cancelRequest(body.message||'Cancel that',{draftId:body.draftId,source:'button'}));return;}
   if(url.pathname==='/api/chat'){if(isCancellationRequest(body.message)){reply(200,await cancelRequest(body.message));return;}if(voiceActive)throw Error('End voice before sending text.');reply(200,await runTurn(body.message,{selectedTask:body.selectedTask}));return;}
   if(url.pathname==='/api/new'){reply(200,newConversation(body));return;}
   if(url.pathname==='/api/approve'){if(voiceActive)throw Error('Approve by voice, or end voice before using the card.');const d=state.drafts.find(d=>d.id===body.draftId&&d.conversationId===active?.id&&d.status==='pending');if(!d)throw Error('Pending draft not found.');reply(200,await runTurn(`I approve the displayed review card: ${d.summary}. Confirm it.`,{approvedDraftId:d.id}));return;}
   if(url.pathname==='/api/reset'){if(busy||voiceActive)throw Error('Finish the active interaction first.');state.config=seed();state.revision++;for(const d of state.drafts)if(d.status==='pending')d.status='superseded';log('system','Demo reset','Usage history retained.');changed();reply(200,snapshot());return;}
   if(url.pathname==='/api/cleanup'){if(busy||voiceActive)throw Error('Finish active work first.');active=null;changed();reply(200,{ok:true});return;}
   reply(404,{error:'Unknown endpoint'});return;
  }
  const allowed={'/cancellation-check':'cancellation-check.html','/cancellation-check.js':'cancellation-check.js','/voice-client.js':'voice-client.js','/transport-check':'transport-check.html','/transport-check.js':'transport-check.js','/':'index.html','/app.js':'app.js','/style.css':'style.css'};
  if(allowed[url.pathname]){const file=allowed[url.pathname];res.writeHead(200,{'Content-Type':file.endsWith('.html')?'text/html':file.endsWith('.css')?'text/css':'text/javascript','Cache-Control':'no-store'});res.end(await fs.readFile(path.join(root,file)));return;}
  if(['/test-audio/request.wav','/test-audio/approval.wav','/test-audio/cancel.wav'].includes(url.pathname)){res.writeHead(200,{'Content-Type':'audio/wav'});res.end(await fs.readFile(path.join(root,'results','transport-check',path.basename(url.pathname))));return;}
  if(/^\/recordings\/[a-zA-Z0-9_-]+\.wav$/.test(url.pathname)){res.writeHead(200,{'Content-Type':'audio/wav'});res.end(await fs.readFile(path.join(resultDir,path.basename(url.pathname))));return;}
  reply(404,{error:'Not found'});
 }catch(e){reply(400,{error:e.message});}
});
const liveInstructions='You are Pulse, a friendly voice interface for a friction app demo. Be brief. Delegate every app question, statistics lookup or configuration change to the function assistant. Your backend can read and update tasks, waits and rewards using fixed app functions and product documentation. Never claim a change succeeded until the backend reports success. Delegate cancellation and undo requests immediately, even when other work is running. Never claim cancellation succeeded until the backend confirms it. Delegate all app-related clarifications and answers, including short yes/no replies, to the backend. Let it ask for missing task preferences. Read its draft-review question faithfully. Never invent app facts or choose missing task requirements yourself. Do not delegate greetings. If asked to change something, acknowledge briefly and delegate once. This is a simulation of app blocking, with real agent execution. When the backend is working, wait for its result; do not repeat the request.';
const rtcSessions=new Map();
const wss=new WebSocketServer({server,maxPayload:200000});
function pcmWav(chunks){const pcm=Buffer.concat(chunks),b=Buffer.alloc(44);b.write('RIFF');b.writeUInt32LE(36+pcm.length,4);b.write('WAVEfmt ',8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(24000,24);b.writeUInt32LE(48000,28);b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(pcm.length,40);return Buffer.concat([b,pcm]);}
wss.on('connection',(client,req)=>handleVoice(client,req));
async function handleVoice(client,req,rtc=null){
  const origin=req.headers.origin;if(origin&&!['http://localhost:'+port,'http://127.0.0.1:'+port].includes(origin)){client.close(1008,'Local origin required');return;}
  if(req.url==='/events'){subscribers.add(client);send(client,{type:'state',state:snapshot()});client.on('close',()=>subscribers.delete(client));return;}
  if(req.url!=='/voice'){client.close();return;}
  try{checkBudget();if(!rtc&&(voiceActive||busy))throw Error('Finish the active interaction first.');}catch(e){send(client,{type:'error',error:{message:e.message}});client.close();return;}
  voiceActive=true;const start=Date.now(),v={id:uuid(),startedAt:start,seconds:0,final:false,transcript:[],sessionId:rtc?.sessionId||null,transport:rtc?'webrtc':'websocket'},audio=[];state.voices.push(v);changed();
  let ready=Boolean(rtc),closing=false,pending='',lastDelegate=0,selectedTask=rtc?.selectedTask||null;const queue=[];let pumping=false;let cancellationEpoch=0;
  const onCancellation=text=>{cancellationEpoch++;queue.length=0;if(live.readyState===1&&!closing)live.send(JSON.stringify({type:'session.commentary.append',delegation_id:null,content:text}));};
  cancellationListeners.add(onCancellation);
  async function pump(){if(pumping)return;pumping=true;try{while(queue.length&&!closing){if(busy){await sleep(100);continue;}const {request,delegation}=queue.shift();const epoch=cancellationEpoch;try{const result=await runTurn(request,{source:'voice',selectedTask});if(!result.cancelled&&epoch===cancellationEpoch&&live.readyState===1&&!closing)live.send(JSON.stringify({type:'session.commentary.append',delegation_id:delegation,content:result.text.slice(0,1500)}));}catch(err){if(live.readyState===1&&!closing)live.send(JSON.stringify({type:'session.commentary.append',delegation_id:delegation,content:'The app operation did not finish: '+err.message.slice(0,600)}));}}}finally{pumping=false;}}
  const handled=new Set();
  const live=new WebSocket(rtc?`wss://api.openai.com/v1/live/sessions/${encodeURIComponent(rtc.sessionId)}/attach`:'wss://api.openai.com/v1/live/sessions',{headers:{Authorization:`Bearer ${key}`}});
  const closeVoice=()=>{if(closing)return;closing=true;if(ready&&live.readyState===1)live.send(JSON.stringify({type:'session.close'}));else live.close();setTimeout(()=>{if(live.readyState!==3)live.terminate();},10000).unref();};
  const limit=setTimeout(closeVoice,120000);
  if(!rtc)live.on('open',()=>live.send(JSON.stringify({type:'session.start',session:{model:'gpt-live-1',instructions:liveInstructions,audio:{format:{type:'audio/pcm',rate:24000},output:{voice:'ripple'}},delegation:{type:'client'}}})));
  live.on('message',async raw=>{
    let e;try{e=JSON.parse(raw);}catch{return;}
    send(client,e);
    if(e.type==='session.started'){ready=true;v.sessionId=e.session.id;log('voice','Live voice connected',e.session.id);changed();}
    if(e.type==='session.input_transcript.delta'){pending+=e.delta||'';v.transcript.push({role:'user',text:e.delta||'',time:Date.now()});broadcast({type:'voice_transcript',role:'user',delta:e.delta||''});}
    if(e.type==='session.output_transcript.delta'){v.transcript.push({role:'assistant',text:e.delta||'',time:Date.now()});broadcast({type:'voice_transcript',role:'assistant',delta:e.delta||''});}
    if(e.type==='session.output_audio.delta')audio.push(Buffer.from(e.delta,'base64'));
    if(e.type==='session.usage.updated'){v.seconds=e.usage?.seconds||v.seconds;changed();}
    if(e.type==='session.delegation.created'){
      const delegation=e.delegation?.id;if(!delegation||handled.has(delegation))return;handled.add(delegation);
      await sleep(500);const request=pending.trim();pending='';
      if(!request){if(live.readyState===1)live.send(JSON.stringify({type:'session.commentary.append',delegation_id:delegation,content:'Please repeat that app request; I did not receive its transcript.'}));return;}
      log('voice','Voice delegated to functions',request);if(isCancellationRequest(request)){try{await cancelRequest(request,{source:'voice'});}catch(err){send(client,{type:'error',error:{message:err.message}});if(live.readyState===1)live.send(JSON.stringify({type:'session.commentary.append',delegation_id:delegation,content:'Cancellation could not finish: '+err.message}));}return;}queue.push({request,delegation});pump();
    }
    if(e.type==='error')log('error','Voice API error',JSON.stringify(e));
    if(e.type==='session.closed'){v.seconds=e.usage?.seconds||v.seconds;v.final=true;v.reason=e.reason;await fs.writeFile(path.join(root,'results','functions-v2',`voice-${v.id}.wav`),pcmWav(audio));v.audioUrl=`/recordings/voice-${v.id}.wav`;log('voice','Voice session ended',`${v.seconds.toFixed(1)} seconds · $${(v.seconds*.05/60).toFixed(4)}`);live.close();}
  });
  client.on('message',raw=>{let e;try{e=JSON.parse(raw);}catch{return;}if(e.type==='app.context'){selectedTask=state.config.tasks.some(t=>t.id===e.selectedTask)?e.selectedTask:null;return;}if(e.type==='session.close'){closeVoice();return;}if(ready&&!closing&&e.type==='session.input_audio.append'&&typeof e.audio==='string'&&e.audio.length<150000)live.send(JSON.stringify(e));});
  client.on('close',closeVoice);
  live.on('error',e=>{log('error','Voice connection error',e.message);send(client,{type:'error',error:{message:e.message}});});
  live.on('close',()=>{cancellationListeners.delete(onCancellation);if(rtc)rtcSessions.delete(rtc.sessionId);clearTimeout(limit);voiceActive=false;if(!v.final){v.seconds=Math.max(v.seconds,(Date.now()-start)/1000);v.estimatedFromClock=true;}send(client,{type:'transport.closed'});client.close();changed();});
if(rtc)await new Promise((resolve,reject)=>{const t=setTimeout(()=>reject(Error('Sideband connection timed out')),15000);live.once('open',()=>{clearTimeout(t);resolve();});live.once('error',e=>{clearTimeout(t);reject(e);});});
}

server.listen(port,'127.0.0.1',()=>console.log(`Pulse functions lab: http://localhost:${port}`));
process.on('SIGINT',async()=>{await save();process.exit();});
