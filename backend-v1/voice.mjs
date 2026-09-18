import WebSocket from 'ws';
import {fail,isCancel} from './domain.mjs';
import {livePrompt} from './prompts.mjs';
import {contextFor} from './agent.mjs';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
// Live context append events allow only 500 tokens. UTF-8 bytes upper-bound tokens.
export function boundedLiveText(value){let out='',bytes=0;for(const ch of String(value)){const n=Buffer.byteLength(ch);if(bytes+n>440)break;out+=ch;bytes+=n;}return out;}
export function terminalCancellation(text){if(isCancel(text))return text;const last=String(text).trim().match(/(?:^|[.!?]\s+)([^.!?]+[.!?]?)$/)?.[1];return last&&isCancel(last)?last:null;}
export class Voice {
 constructor(store,agent,{key=process.env.OPENAI_API_KEY,fetcher=fetch,maxSeconds=Number(process.env.VOICE_MAX_SECONDS||300),cancellationDelayMs=900,delegationDelayMs=350,progressDelayMs=4000}={}){this.store=store;this.agent=agent;this.key=key;this.fetcher=fetcher;this.maxSeconds=Math.max(30,Math.min(maxSeconds,900));this.cancellationDelayMs=cancellationDelayMs;this.delegationDelayMs=delegationDelayMs;this.progressDelayMs=progressDelayMs;this.sessions=new Map();store.on('change',e=>this.update(e));}
 async create(install,sdp){
  fail(this.key,'Voice is not configured yet.',503);fail(typeof sdp==='string'&&sdp.startsWith('v=0')&&sdp.length<100000,'A WebRTC audio offer is required.');const runtime=this.store.getRuntime(install);fail(!runtime.voice,'A voice call is already active.',409);runtime.voice={connecting:true};let reservation;try{reservation=await this.store.reserve(install,'voice',this.maxSeconds*.05/60);}catch(e){runtime.voice=null;throw e;}let created;
  try{
   const response=await this.fetcher('https://api.openai.com/v1/live/sessions',{method:'POST',headers:{Authorization:`Bearer ${this.key}`,'Content-Type':'application/json'},body:JSON.stringify({session:{model:'gpt-live-1',instructions:livePrompt(contextFor(this.store,install)),audio:{output:{voice:'ripple'}},delegation:{type:'client'}},transport:{type:'webrtc',sdp}}),signal:AbortSignal.timeout(20000)});
   created=await response.json();fail(response.ok,'Voice connection could not be created. Try again.',502);
   const sessionId=created.session.id,socket=new WebSocket(`wss://api.openai.com/v1/live/sessions/${encodeURIComponent(sessionId)}/attach`,{headers:{Authorization:`Bearer ${this.key}`}});
   const session={installationId:install,sessionId,socket,reservation,startedAt:Date.now(),seconds:0,closed:false,pending:'',pendingVersion:0,inputEvents:new Set(),queue:[],pumping:false,handled:new Set(),epoch:0};this.sessions.set(sessionId,session);runtime.voice={sessionId};
   session.timer=setTimeout(()=>this.close(install,sessionId,'duration_limit'),this.maxSeconds*1000);
   socket.on('message',raw=>this.event(session,raw).catch(e=>{this.comment(session,'That request could not finish: '+e.message);this.store.notify(install,'voice_error',{error:e.message});}));
   socket.on('error',()=>this.store.notify(install,'voice_error',{error:'Voice connection interrupted.'}));
   socket.on('close',()=>this.finished(session));
   await new Promise((resolve,reject)=>{const t=setTimeout(()=>reject(Error('Voice sideband timed out.')),15000);socket.once('open',()=>{clearTimeout(t);resolve();});socket.once('error',()=>{clearTimeout(t);reject(Error('Voice sideband connection failed.'));});});
   this.store.notify(install,'voice_started',{sessionId});return{sessionId,sdp:created.transport.sdp};
  }catch(e){runtime.voice=null;const stale=created?.session?.id?this.sessions.get(created.session.id):null;if(created?.session?.id)await this.hangup(created.session.id);if(stale){stale.socket.terminate();await this.finished(stale);}else await this.store.settle(reservation,created?.session?.id?null:0,{reason:'Voice connection failed.'});throw e;}
 }
 append(s,type,content,delegation=null){if(s.socket.readyState===1&&!s.closed)s.socket.send(JSON.stringify({type,delegation_id:delegation,content:boundedLiveText(content)}));}
 comment(s,content,delegation=null){this.append(s,'session.commentary.append',content,delegation);}
 quiet(s,content){this.append(s,'session.thinking.append',content);}
 cardContext(card){const summarize=value=>value&&typeof value==='object'&&!Array.isArray(value)?{name:value.name,verifier:value.verifier,metric:value.metric,unitsPerCredit:value.unitsPerCredit,paused:value.paused}:value;return `${card.id} (${card.title}): ${JSON.stringify(summarize(card.before))} -> ${JSON.stringify(summarize(card.after))}`;}
 update({installationId,kind,detail}){for(const s of this.sessions.values()){
  if(s.installationId!==installationId)continue;
  if(kind==='cancelled'){s.epoch++;s.queue=[];clearTimeout(s.progressTimer);this.comment(s,detail.restored?'Undo is saved and verified. Wait for Android acknowledgement before claiming it is active.':'The pending request is cancelled. No changes from it will be applied.');}
  else if(kind==='saved'){this.comment(s,'The reviewed changes are saved and verified. Android has not acknowledged them yet.');for(const card of detail.change?.cards||[])this.quiet(s,`Saved revision ${detail.revision}. `+this.cardContext(card));}
  else if(kind==='applied')this.comment(s,detail.applicationStatus==='applied'?'Android confirms revision '+detail.revision+' is active. You may now say the changes are applied.':'Android is still applying the newest revision.');
  else if(kind==='proposal'){this.quiet(s,`Review ${detail.id}, version ${detail.version}, ${detail.cards.length} cards. Title: ${detail.title}. User may approve using Apply or voice.`);for(const card of detail.cards)this.quiet(s,this.cardContext(card));}
  else if(kind==='context'){
   const state=this.store.get(installationId),changedCard=s.lastCardId!==state.selectedCardId;if(!changedCard&&Date.now()-(s.lastContextAt||0)<10000)continue;s.lastContextAt=Date.now();s.lastCardId=state.selectedCardId;
   const proposal=state.proposals.at(-1),card=proposal?.cards.find(c=>c.id===state.selectedCardId);
   if(changedCard&&card)this.quiet(s,'The user is looking at this card: '+this.cardContext(card));
   else this.quiet(s,`Device context refreshed. Saved revision ${state.revision}; Android applied revision ${state.appliedRevision}. Latest measured usage summary: ${JSON.stringify(state.statistics.summary)}`);
  }else if(kind==='receipt')this.quiet(s,`Activity verified: ${detail.task?.name||detail.taskId}, measured ${detail.measuredUnits} ${detail.metric}. Daily progress eligible: ${detail.rewardEligible}. Browsing minutes eligible: ${detail.minutesRewardEligible??detail.rewardEligible}. Android calculates the reward.`);
 }}
 // Live has no authoritative utterance-complete event, and it may not delegate an
 // interruption. Only whole explicit commands are routed without a delegation.
 // The stability interval is an app policy: every further fragment cancels the
 // candidate, including a continuation such as "cancel that would be a mistake".
 input(s,e){
  s.inputEvents??=new Set();if(e.event_id&&s.inputEvents.has(e.event_id))return;
  if(e.event_id){s.inputEvents.add(e.event_id);if(s.inputEvents.size>3000)s.inputEvents.delete(s.inputEvents.values().next().value);}
  if(typeof e.delta!=='string'||!e.delta)return;
  clearTimeout(s.cancellationTimer);s.pending=(s.pending+e.delta).slice(-12000);s.pendingVersion=(s.pendingVersion||0)+1;s.inputEndMs=Math.max(s.inputEndMs||0,e.end_ms||0);
  const request=s.pending.trim(),version=s.pendingVersion;
  if(!isCancel(request))return;
  s.cancellationTimer=setTimeout(()=>{if(s.closed||s.pendingVersion!==version||s.pending.trim()!==request)return;this.cancelTranscript(s,request).catch(error=>this.cancellationError(s,error));},this.cancellationDelayMs);
 }
 cancellationError(s,error){this.comment(s,'Cancellation could not be confirmed. Please use the Cancel button or try again.');this.store.notify(s.installationId,'voice_error',{error:error.message});}
 async cancelTranscript(s,request){
  if(s.closed||!isCancel(request))return;
  clearTimeout(s.cancellationTimer);clearTimeout(s.progressTimer);
  const version=s.pendingVersion,throughMs=s.inputEndMs||0;s.pending='';s.pendingVersion=(s.pendingVersion||0)+1;s.queue=[];s.epoch++;
  const cancellation={version,throughMs,at:Date.now(),result:null};s.lastCancellation=cancellation;
  // Bind this operation to the server-owned session identity. User/assistant
  // messages, durable cancellation and read-back use the same path as text/UI.
  const job=this.agent.chat(s.installationId,request,{source:'voice'});s.cancelling=job;
  try{cancellation.result=await job;return cancellation.result;}finally{if(s.cancelling===job)s.cancelling=null;}
 }
 async event(s,raw){let e;try{e=JSON.parse(raw);}catch{return;}if(s.closed)return;
  if(e.type==='session.input_transcript.delta')this.input(s,e);
  if(e.type==='session.usage.updated')s.seconds=e.usage?.seconds||s.seconds;
  if(e.type==='error')this.store.notify(s.installationId,'voice_error',{error:e.error?.message||e.message||'Voice error.'});
  if(e.type==='session.closed'){s.seconds=e.usage?.seconds||s.seconds;s.finalUsage=Number.isFinite(e.usage?.seconds);s.socket.close();return;}
  if(e.type!=='session.delegation.created')return;const delegation=e.delegation?.id;if(!delegation||s.handled.has(delegation))return;s.handled.add(delegation);const epoch=s.epoch;await sleep(this.delegationDelayMs);if(s.closed||epoch!==s.epoch)return;
  // A delayed provider delegation can refer to a command already executed from
  // the transcript. It must not consume a newer request or repeat the old work.
  if(s.lastCancellation&&Number.isFinite(e.offset_ms)&&e.offset_ms<=s.lastCancellation.throughMs)return;
  const request=s.pending.trim();if(!request){if(s.lastCancellation)return;this.comment(s,'Please repeat the app request; its transcript was not received.',delegation);return;}
  if(isCancel(request))return; // The same stability timer handles both routes.
  clearTimeout(s.cancellationTimer);s.pending='';s.pendingVersion=(s.pendingVersion||0)+1;
  s.queue.push({request,delegation});this.pump(s);
 }
 async pump(s){if(s.pumping)return;s.pumping=true;try{while(s.queue.length&&!s.closed){if(s.cancelling||this.store.getRuntime(s.installationId).turn){await sleep(100);continue;}const {request,delegation}=s.queue.shift(),epoch=s.epoch;
  const progress=setTimeout(()=>{if(!s.closed&&epoch===s.epoch&&!this.store.getRuntime(s.installationId).turn?.cancelled)this.comment(s,'Still preparing your requested changes. The review will appear when it is ready. Keep listening, and avoid repeating the acknowledgement.',delegation);},this.progressDelayMs);s.progressTimer=progress;
  try{const result=await this.agent.chat(s.installationId,request,{source:'voice'});if(!result.cancelled&&epoch===s.epoch)this.comment(s,result.text,delegation);}catch(e){if(epoch===s.epoch)this.comment(s,'The request did not complete: '+e.message,delegation);}finally{clearTimeout(progress);if(s.progressTimer===progress)s.progressTimer=null;}
 }}finally{s.pumping=false;}}
 async hangup(sessionId){await this.fetcher(`https://api.openai.com/v1/live/sessions/${encodeURIComponent(sessionId)}/hangup`,{method:'POST',headers:{Authorization:`Bearer ${this.key}`},signal:AbortSignal.timeout(10000)}).catch(()=>{});}
 async close(install,sessionId,reason='user'){const s=this.sessions.get(sessionId);fail(!s||s.installationId===install,'Voice session not found.',404);if(!s)return{ok:true};s.endReason??=reason;this.store.getRuntime(install).lastVoiceEnd={sessionId,reason:s.endReason,maxSeconds:this.maxSeconds};if(s.socket.readyState===1)s.socket.send(JSON.stringify({type:'session.close'}));await this.hangup(sessionId);s.socket.close();setTimeout(()=>{s.socket.terminate();this.finished(s);},3000).unref();return{ok:true};}
 async finished(s){if(s.closed)return;s.closed=true;clearTimeout(s.timer);clearTimeout(s.cancellationTimer);clearTimeout(s.progressTimer);s.queue=[];this.sessions.delete(s.sessionId);const runtime=this.store.getRuntime(s.installationId);if(runtime.voice?.sessionId===s.sessionId)runtime.voice=null;const seconds=s.finalUsage?s.seconds:Math.max(s.seconds,(Date.now()-s.startedAt)/1000);await this.store.settle(s.reservation,seconds*.05/60,{model:'gpt-live-1',voice:'ripple',seconds,estimated:true,costBasis:'published-rates-not-invoice',clockEstimate:!s.finalUsage});this.store.notify(s.installationId,'voice_closed');}
}
