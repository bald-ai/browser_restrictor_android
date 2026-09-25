import {assistantSettingsFor,assistantOptions,validateAssistantSettings} from './assistant-settings.mjs';
import {captureBase,expandChanges} from './edits.mjs';
import fs from 'node:fs/promises';
import path from 'node:path';
import {EventEmitter} from 'node:events';
import {AsyncLocalStorage} from 'node:async_hooks';
import {AppError,fail,id,seed,clone,hash,validateConfig,diff,applyCards,periodKey} from './domain.mjs';
function samePreview(old,body){return (old.proposalId||null)===(body.proposalId||null)&&(old.proposalVersion||null)===(body.proposalVersion||null);}
function validateAttemptRetry(old,body){fail(old.task.id===body.taskId&&old.startedAt===body.startedAt&&old.configRevision===body.configRevision&&samePreview(old,body),'Attempt ID belongs to a different recording.',409);}
function attemptConfiguration(state,body,revision){
 if(!body.proposalId)return {config:revision.config,preview:false};
 const proposal=state.proposals.find(p=>p.id===body.proposalId);
 fail(proposal?.status==='pending'&&proposal.version===body.proposalVersion&&proposal.baseRevision===state.revision&&body.configRevision===state.revision,'The review changed. Reopen its latest card before testing.',409);
 return {config:applyCards(state.config,proposal.cards),preview:true};
}
function validateRecordingEnd(attempt,endedAt,now){
 fail(Number.isFinite(endedAt)&&endedAt>=attempt.startedAt&&endedAt<=now+60000&&endedAt-attempt.startedAt<=4*60*60*1000+1000,'Invalid recording end or recording exceeded four hours.');
}
function receiptFlags(attempt,details,now){
 const rewardEligible=!attempt.preview&&periodKey(details.endedAt,attempt.settings.dayReset,details.timeZone)===periodKey(now,attempt.settings.dayReset,details.timeZone);
 return {rewardEligible,minutesRewardEligible:rewardEligible,
  correctionEligible:!attempt.preview&&details.measuredUnits>=attempt.task.correctionMinimum,
  fallbackEligible:!attempt.preview&&Boolean(details.fallbackEligible)&&attempt.task.verifier==='gps'};
}
function recordingCorrectionMaximum(attempt,details){
 if(attempt.task.verifier!=='video'||attempt.task.metric!=='seconds')return null;
 const sourceSeconds=Number.isFinite(details.diagnostics?.sourceSeconds)?details.diagnostics.sourceSeconds:30.5;
 return Math.floor(Math.min(30.5,Math.min((details.endedAt-attempt.startedAt)/1000,sourceSeconds)+.1)*10)/10;
}
function verifiedReceipt(a,attemptId,details,now){
 return {id:id(),attemptId,preview:Boolean(a.preview),proposalId:a.proposalId||null,proposalVersion:a.proposalVersion||null,
  taskId:a.task.id,task:clone(a.task),configRevision:a.configRevision,startedAt:a.startedAt,endedAt:details.endedAt,verifiedAt:now,
  verifier:a.task.verifier,metric:a.task.metric,measuredUnits:details.measuredUnits,correctionMinimum:a.task.correctionMinimum,
  unitsPerCredit:a.task.unitsPerCredit,minutesPerCredit:a.settings.minutesPerCredit,dayReset:a.settings.dayReset,timeZone:details.timeZone,
  ...receiptFlags(a,details,now),reason:String(details.reason||'').slice(0,2000),confidence:details.confidence??null,
  diagnostics:details.diagnostics||null,maximumCorrectableUnits:recordingCorrectionMaximum(a,details)};
}
function repeatsGenericCancellation(s,generic,inflight,runtime){return generic&&!inflight&&!runtime.savingProposal&&s.lastGenericCancellation?.editEpoch===(s.editEpoch||0);}
function explicitCancellationTarget(s,proposalId,changeId){
 let p=proposalId?s.proposals.find(p=>p.id===proposalId):null;fail(!proposalId||p,'Review not found.',404);
 let c=changeId?s.changes.find(c=>c.id===changeId&&!c.undoOf):p?.changeId?s.changes.find(c=>c.id===p.changeId):null;fail(!changeId||c,'Change not found.',404);
 return {p,c};
}
function cancellationTarget(s,proposalId,changeId,inflight){
 let {p,c}=explicitCancellationTarget(s,proposalId,changeId);
 if(!p&&!c){
  p=inflight?s.proposals.findLast(p=>p.status==='pending'&&p.originTurn===inflight.id):s.proposals.at(-1);
  if(!p&&!inflight)c=s.changes.findLast(c=>!c.undoOf);
 }
 return {p,c};
}
function linkedCancellationChange(s,p,inflight){
 let c=null;if(p?.changeId)c=s.changes.find(c=>c.id===p.changeId);
 if(!c&&inflight)c=s.changes.findLast(c=>c.originTurn===inflight.id&&!c.undoOf);
 return c;
}
function cancelPendingReview(p,cardId){
 if(!cardId){p.status='cancelled';return;}
 fail(p.cards.some(c=>c.id===cardId),'Card not found.',404);p.cards=p.cards.filter(c=>c.id!==cardId);p.version++;
 if(!p.cards.length)p.status='cancelled';
}
function validateAttemptId(value){fail(typeof value==='string'&&/^[a-zA-Z0-9_-]{8,80}$/.test(value),'A unique attempt ID is required.');}
export class Store extends EventEmitter {
 constructor(dir,{clock=Date.now,beforeWrite=null}={}){super();this.dir=dir;this.file=path.join(dir,'friction-v1.json');this.clock=clock;this.beforeWrite=beforeWrite;this.queue=Promise.resolve();this.runtime=new Map();this.transactionContext=new AsyncLocalStorage();}
 async init(){await fs.mkdir(this.dir,{recursive:true});try{this.db=JSON.parse(await fs.readFile(this.file,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;this.db={schema:1,installations:{},usage:[],reservations:{}};await this.persist();}this.db.reservations??={};if(this.migrateDailyBudget())await this.persist();return this;}
 migrateDailyBudget(){
  if((this.db.schema||1)>=2)return false;
  for(const state of Object.values(this.db.installations))this.migrateInstallationBudget(state);
  this.db.schema=2;return true;
 }
 migrateInstallationBudget(s){
   const config=clone(s.config);delete config.settings.budgetReset;config.settings.dailyBudgetMinutes??=0;
   s.config=validateConfig(config);s.revision++;s.editEpoch=(s.editEpoch||0)+1;
   s.revisions.push({revision:s.revision,at:this.clock(),config:clone(s.config)});
   // Remove retired controls from reviews/undo, preserving all other edits and history.
   const keep=card=>!(card.kind==='setting'&&card.key==='budgetReset');
   for(const p of s.proposals){p.cards=p.cards.filter(keep);if(p.status==='pending'){p.baseRevision=s.revision;p.version++;if(!p.cards.length)p.status='cancelled';}}
   for(const c of s.changes)c.cards=c.cards.filter(keep);
   if(s.selectedCardId==='setting:budgetReset')s.selectedCardId=null;
   if(s.statistics?.summary)delete s.statistics.summary.budgetReset;
 }
 async persist(database=this.db){await this.beforeWrite?.();const temp=this.file+'.tmp';await fs.writeFile(temp,JSON.stringify(database),{mode:0o600});await fs.rename(temp,this.file);}
 transaction(fn){const job=this.queue.catch(()=>{}).then(async()=>{const working=clone(this.db);return this.transactionContext.run(working,async()=>{const value=await fn(working);await this.persist(working);this.db=working;return clone(value);});});this.queue=job;return job;}
 get(install){const database=this.transactionContext.getStore()||this.db;const s=database.installations[install];fail(s,'Installation not found.',401);return s;}
 authenticate(token){fail(typeof token==='string'&&token.length>=32,'Installation credentials required.',401);const tokenHash=hash(token);const s=Object.values(this.db.installations).find(s=>s.tokenHash===tokenHash);fail(s,'Installation credentials are invalid.',401);return s.installationId;}
 async register(requested){const result=await this.transaction(db=>{const installationId=requested||id();fail(/^[a-zA-Z0-9_-]{8,80}$/.test(installationId),'Invalid installation ID.');fail(!db.installations[installationId],'This installation is already registered; use its saved credentials.',409);fail(Object.keys(db.installations).length<10000,'Installation limit reached.',429);const token=id()+id();const config=seed();db.installations[installationId]={installationId,tokenHash:hash(token),createdAt:this.clock(),config,revision:0,appliedRevision:-1,revisions:[{revision:0,at:this.clock(),config:clone(config)}],proposals:[],changes:[],operations:{},messages:[],statistics:{summary:null,events:[],updatedAt:null},attempts:{},selectedCardId:null};return{installationId,token,config,revision:0};});return result;}
 envelope(s){const latest=s.changes.at(-1);const undoableChangeId=latest&&!latest.undoOf&&latest.revision===s.revision&&!s.proposals.some(p=>p.status==='pending')?latest.id:null;return{undoableChangeId,config:clone(s.config),revision:s.revision,configHash:hash(s.config),appliedRevision:s.appliedRevision,applicationStatus:s.appliedRevision===s.revision?'applied':'awaiting_device'};}
 snapshot(install){const s=this.get(install),r=this.runtime.get(install);return{installationId:install,...this.envelope(s),proposals:clone(s.proposals.slice(-12)),changes:clone(s.changes.slice(-15)),messages:clone(s.messages.slice(-30)),statistics:clone(s.statistics),selectedCardId:s.selectedCardId,previewResults:clone(Object.values(s.attempts).filter(a=>a.preview&&a.receipt).map(a=>a.receipt).slice(-10)),busy:Boolean(r?.turn),cancelling:Boolean(r?.turn?.cancelled),voiceActive:Boolean(r?.voice),lastVoiceEnd:r?.lastVoiceEnd||null,...assistantSettingsFor(s),assistantSettings:assistantSettingsFor(s),assistantOptions:clone(assistantOptions),voice:'ripple',usage:this.usage(install),budget:null};}
 async setAssistantSettings(install,value){const settings=validateAssistantSettings(value);await this.transaction(()=>{this.get(install).assistantSettings=settings;return{};});this.notify(install,'assistant_settings');return this.snapshot(install);}
 usage(install=null){const usage=this.db.usage.filter(u=>!install||u.installationId===install);return{usd:usage.reduce((a,u)=>a+u.usd,0),estimated:usage.some(u=>u.estimated||['astra','voice'].includes(u.type)),calls:usage.length,entries:clone(usage.slice(-10))};}
 getRuntime(install){if(!this.runtime.has(install))this.runtime.set(install,{});return this.runtime.get(install);}
 notify(install,kind='state',detail=null){this.emit('change',{installationId:install,kind,detail});}
 async message(install,role,text,source='text'){await this.transaction(()=>{const s=this.get(install);s.messages.push({id:id(),role,text:String(text).slice(0,6000),source,at:this.clock()});s.messages=s.messages.slice(-100);return{};});this.notify(install);}
 async reserve(install,type,amount){return this.transaction(db=>{const key=id();db.reservations[key]={installationId:install,type,amount,at:this.clock()};return key;});}
 async settle(key,usd,details={}){return this.transaction(db=>{const reservation=db.reservations[key];if(!reservation)return{};delete db.reservations[key];db.usage.push({id:key,...reservation,...details,usd:Number.isFinite(usd)&&usd>=0?usd:reservation.amount,estimated:details.estimated??!Number.isFinite(usd),at:this.clock()});return{};});}
 async release(key){return this.transaction(db=>{delete db.reservations[key];return{};});}
 async ack(install,revision,configHash){let advanced=false;const out=await this.transaction(()=>{const s=this.get(install);fail(Number.isInteger(revision)&&revision>=0&&revision<=s.revision,'Unknown configuration revision.',409);const entry=s.revisions.find(r=>r.revision===revision);fail(entry,'Configuration revision not retained.',409);fail(!configHash||configHash===hash(entry.config),'The device applied a different configuration.',409);advanced=revision>s.appliedRevision;s.appliedRevision=Math.max(s.appliedRevision,revision);if(advanced)s.appliedAt=this.clock();return this.envelope(s);});if(advanced)this.notify(install,'applied',out);return out;}
 async statistics(install,body){fail(body.summary&&typeof body.summary==='object'&&!Array.isArray(body.summary),'A measured usage summary is required.');fail(!body.events||Array.isArray(body.events),'Activity events must be a list.');fail(JSON.stringify(body).length<100000,'Usage summary is too large.');let changed=false;await this.transaction(()=>{const s=this.get(install),next={summary:clone(body.summary),events:clone((body.events||[]).slice(-100)),history:body.history?clone(body.history):s.statistics.history||null};changed=hash(next)!==hash({summary:s.statistics.summary,events:s.statistics.events,history:s.statistics.history||null});s.statistics={...next,updatedAt:this.clock()};return{};});if(changed)this.notify(install,'context');return{ok:true};}
 async context(install,cardId){const out=await this.transaction(()=>{const s=this.get(install);fail(!cardId||s.proposals.some(p=>p.cards.some(c=>c.id===cardId)),'This review card no longer exists.',409);s.selectedCardId=cardId||null;return{ok:true};});this.notify(install,'context');return out;}
 async stage(install,{config,changes,expectedRevision,title='Review your changes'},ctx=null){
 const out=await this.transaction(()=>{fail(!ctx?.cancelled,'This request was cancelled.',409);const s=this.get(install);fail(s.revision===expectedRevision,'Settings changed while this request was being prepared. Please review the current setup.',409);if(changes!==undefined)config=this.expandStagedChanges(install,config,changes,ctx);config=validateConfig(config);const cards=diff(s.config,config);fail(cards.length,'These values are already set.');let p=s.proposals.findLast(p=>p.status==='pending');if(p&&p.baseRevision===s.revision){p.version++;p.cards=cards;p.title=String(title).slice(0,160);p.originTurn=ctx?.id||null;}else{if(p)p.status='cancelled';p={id:id(),version:1,baseRevision:s.revision,title:String(title).slice(0,160),status:'pending',cards,originTurn:ctx?.id||null,createdAt:this.clock()};s.proposals.push(p);}s.selectedCardId=cards[0].id;s.editEpoch=(s.editEpoch||0)+1;return clone(p);});this.notify(install,'proposal',out);return out;
 }
 expandStagedChanges(install,config,changes,ctx){fail(config===undefined,'Choose explicit edits or a full manual configuration.');const current=captureBase(this,install);if(ctx?.editBase){const base=ctx.editBase;fail(base.revision===current.revision&&base.proposalId===current.proposalId&&base.version===current.version,'The setup or review changed while this edit was being prepared.',409);}return expandChanges(current.config,changes);}
 async remove(install,proposalId,version,cardId){const out=await this.transaction(()=>{const s=this.get(install),p=s.proposals.find(p=>p.id===proposalId);fail(p?.status==='pending'&&p.version===version,'This review changed. Please review it again.',409);fail(p.cards.some(c=>c.id===cardId),'Card not found.',404);p.cards=p.cards.filter(c=>c.id!==cardId);p.version++;if(!p.cards.length)p.status='cancelled';s.selectedCardId=p.cards[0]?.id||null;s.editEpoch=(s.editEpoch||0)+1;return p;});this.notify(install,'proposal',out);return out;}
 commit(s,config,{proposalId=null,undoOf=null,cards,originTurn=null}={}){
 const before=clone(s.config);s.config=validateConfig(config);s.revision++;s.editEpoch=(s.editEpoch||0)+1;s.revisions.push({revision:s.revision,at:this.clock(),config:clone(s.config)});const change={id:id(),revision:s.revision,proposalId,undoOf,originTurn,cards:cards||diff(before,s.config),undoneCardIds:[],verified:false,at:this.clock()};s.changes.push(change);return change;
 }
 async confirm(install,proposalId,{version,operationId=id()}={},ctx=null){
 const runtime=this.getRuntime(install);runtime.savingProposal=proposalId;
 try{const out=await this.transaction(()=>{const s=this.get(install);fail(!ctx?.cancelled,'This request was cancelled.',409);const old=s.operations[operationId];if(old){fail(old.kind==='confirm'&&old.target===proposalId,'Operation ID was used for a different action.',409);return{change:s.changes.find(c=>c.id===old.changeId),...this.envelope(s)};}
  const p=s.proposals.find(p=>p.id===proposalId);fail(p,'Review not found.',404);fail(!ctx||p.originTurn!==ctx.id,'A newly proposed review needs approval in a later user turn.',409);
  if(p.status==='saved'){fail(p.version===version,'This approval refers to a different review.',409);return{change:s.changes.find(c=>c.id===p.changeId),...this.envelope(s)};}
  fail(p.status==='pending'&&p.version===version,'This review changed or was cancelled. Please review the latest cards.',409);fail(p.baseRevision===s.revision,'Your setup changed. Make a new review before applying.',409);
  const change=this.commit(s,applyCards(s.config,p.cards),{proposalId,cards:clone(p.cards),originTurn:ctx?.id||null});p.status='saved';p.changeId=change.id;s.operations[operationId]={kind:'confirm',target:proposalId,changeId:change.id};return{change,...this.envelope(s)};
 });
 await this.verifySaved(install,out);this.notify(install,'saved',out);return out;
 }finally{if(runtime.savingProposal===proposalId)runtime.savingProposal=null;}
 }
 async verifySaved(install,out){const persisted=JSON.parse(await fs.readFile(this.file,'utf8')).installations[install],entry=persisted.revisions.find(r=>r.revision===out.revision);fail(entry&&hash(entry.config)===hash(out.config),'Saved configuration read-back did not match.',500);if(out.change){await this.transaction(()=>{const c=this.get(install).changes.find(c=>c.id===out.change.id);fail(c,'Saved change receipt missing.',500);c.verified=true;return{};});out.change.verified=true;}return out;}
 async cancel(install,{proposalId=null,changeId=null,cardId=null,operationId=id()}={}){
 const runtime=this.getRuntime(install),inflight=runtime.turn,generic=!proposalId&&!changeId&&!cardId;
 const signature=hash({proposalId,changeId,cardId});proposalId=proposalId||runtime.savingProposal||null;if(inflight)inflight.cancelled=true;
 const out=await this.transaction(()=>{const s=this.get(install),previous=s.operations[operationId];if(previous){fail(previous.kind==='cancel'&&previous.signature===signature,'Operation ID belongs to another action.',409);return{cancelled:true,restored:previous.restored,...this.envelope(s)};}
  const finish=result=>{s.operations[operationId]={kind:'cancel',signature,restored:result.restored,changeId:result.change?.id};if(generic)s.lastGenericCancellation={editEpoch:s.editEpoch||0,restored:result.restored};return result;};
  if(repeatsGenericCancellation(s,generic,inflight,runtime))return finish({cancelled:true,restored:s.lastGenericCancellation.restored,alreadyCancelled:true,...this.envelope(s)});
  let {p,c}=cancellationTarget(s,proposalId,changeId,inflight);
  if(p?.status==='pending'){cancelPendingReview(p,cardId);return finish({cancelled:true,restored:false,...this.envelope(s)});}
  if(!c)c=linkedCancellationChange(s,p,inflight);
  if(!c)return finish({cancelled:true,restored:false,...this.envelope(s)});
  return finish(this.undoLatestChange(s,c,cardId));
 });await this.verifySaved(install,out);this.notify(install,'cancelled',out);return out;
 }
 undoLatestChange(s,c,cardId){
  fail(!cardId,'Undo restores the whole latest action; individual saved changes cannot be undone.',409);
  if(c.undoneCardIds.length===c.cards.length)return {cancelled:true,restored:true,alreadyCancelled:true,...this.envelope(s)};
  fail(s.changes.at(-1)?.id===c.id&&!c.undoOf&&c.revision===s.revision,'Only the latest saved action can be undone.',409);
  fail(!s.proposals.some(p=>p.status==='pending'),'Cancel the pending review before undoing the latest saved action.',409);
  const before=s.revisions.find(r=>r.revision===c.revision-1);
  fail(before,'The previous configuration is unavailable; this action cannot be undone.',409);
  const undo=this.commit(s,clone(before.config),{undoOf:c.id});c.undoneCardIds=c.cards.map(card=>card.id);const linked=s.proposals.find(p=>p.id===c.proposalId);if(linked){linked.undoChangeId=undo.id;linked.status='undone';}
  return {cancelled:true,restored:true,change:undo,...this.envelope(s)};
 }
 async startAttempt(install,body){return this.transaction(()=>{
  const state=this.get(install);
  validateAttemptId(body.attemptId);
  const old=state.attempts[body.attemptId];if(old){validateAttemptRetry(old,body);return old;}
  fail(Number.isFinite(body.startedAt)&&body.startedAt>0&&body.startedAt<=this.clock()+60000,'Invalid recording start time.');
  const revision=state.revisions.find(r=>r.revision===body.configRevision);fail(revision,'Unknown configuration revision.',409);
  const {config,preview}=attemptConfiguration(state,body,revision);
  const task=config.tasks.find(t=>t.id===body.taskId);fail(task&&(preview||!task.paused),'This task is unavailable in that configuration.');
  const attempt={attemptId:body.attemptId,task:clone(task),taskId:task.id,configRevision:revision.revision,settings:clone(config.settings),startedAt:body.startedAt,createdAt:this.clock(),preview,proposalId:body.proposalId||null,proposalVersion:body.proposalVersion||null};
  state.attempts[body.attemptId]=attempt;return attempt;
 });}
 async finishAttempt(install,attemptId,{endedAt,timeZone='UTC',measuredUnits,reason,confidence,diagnostics,fallbackEligible=false}){
  const out=await this.transaction(()=>{
   const attempt=this.get(install).attempts[attemptId];fail(attempt,'Recording not found.',404);if(attempt.receipt)return attempt.receipt;
   validateRecordingEnd(attempt,endedAt,this.clock());
   fail(Number.isFinite(measuredUnits)&&measuredUnits>=0,'Invalid verification measurement.',502);
   const receipt=verifiedReceipt(attempt,attemptId,{endedAt,timeZone,measuredUnits,reason,confidence,diagnostics,fallbackEligible},this.clock());
   attempt.endedAt=endedAt;attempt.receipt=receipt;return receipt;
  });this.notify(install,'receipt',out);return out;
 }
 receiptCurrent(r){return r.rewardEligible&&(r.minutesRewardEligible??r.rewardEligible)&&periodKey(r.endedAt,r.dayReset,r.timeZone)===periodKey(this.clock(),r.dayReset,r.timeZone);}
 async fallback(install,attemptId,completed){const out=await this.transaction(()=>{const r=this.get(install).attempts[attemptId]?.receipt;fail(r,'Verified recording not found.',404);fail(this.receiptCurrent(r),'This reward period has ended; manual completion can no longer grant a reward.',409);fail(completed===true&&r.fallbackEligible,'Manual completion is available only when GPS coverage was inadequate.',409);r.manualCompletion=true;r.correctedUnits=r.unitsPerCredit;r.correctedAt=this.clock();return r;});this.notify(install,'receipt',out);return out;}
 async correct(install,attemptId,value){const out=await this.transaction(()=>{const a=this.get(install).attempts[attemptId],r=a?.receipt;fail(r,'Verified recording not found.',404);fail(this.receiptCurrent(r),'This reward period has ended; it can no longer be corrected.',409);fail(!r.manualCompletion,'This recording used manual completion for one credit; it cannot also be corrected.',409);fail(r.correctionEligible,'The recording did not meet its correction minimum.',409);fail(Number.isFinite(value)&&value>=0&&value<=100000&&(r.metric!=='repetitions'||Number.isInteger(value)),'Correction must be a nonnegative measurement.');if(r.metric==='seconds')fail(value<=(r.maximumCorrectableUnits??Math.min((r.endedAt-r.startedAt)/1000,Number.isFinite(r.diagnostics?.sourceSeconds)?r.diagnostics.sourceSeconds:30.5)+.1),'Corrected time cannot exceed the recorded video.');r.correctedUnits=value;r.correctedAt=this.clock();return r;});this.notify(install,'receipt',out);return out;}
}
