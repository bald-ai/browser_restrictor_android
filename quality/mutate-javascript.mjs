import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
const root=path.resolve(import.meta.dirname,'..');
if (!process.env.VERIFICATION_SESSION_ROOT) {
 const child=spawnSync('python3',[path.join(root,'scripts/verification-session.py'),'mutations',...process.argv.slice(2)],{stdio:'inherit'});
 process.exit(child.status??1);
}
const runId='javascript-'+Date.now(),logs=path.join(root,'quality/logs',runId);await fs.mkdir(logs,{recursive:true});
const specs=[
 ['gps-chronology','evidence.mjs','p.time>this.lastTime&&','',['gps-rules']],
 ['configuration-bounds','domain.mjs','integer(s[k],min,max)','true',['config-validation']],
 ['recording-preview-identity','store.mjs','&&samePreview(old,body)','',['recording-policy']],
 ['recording-reward-eligibility','store.mjs','const rewardEligible=!attempt.preview&&','const rewardEligible=',['core']],
 ['concurrent-video-retry','server.mjs','if(attemptsRunning.has(operationKey))','if(false)',['http']],
 ['recording-verifier-match','server.mjs','fail(attempt.task.verifier===method,','fail(true,',['http']],
 ['cancel-linked-change','store.mjs','if(!c&&p?.changeId)c=s.changes.find(c=>c.id===p.changeId);','',['core']],
 ['cancel-inflight-change','store.mjs','if(!c&&inflight)c=s.changes.findLast(c=>c.originTurn===inflight.id&&!c.undoOf);','',['core']],
 ['cancel-undo-change-target','store.mjs','s.changes.find(c=>c.id===changeId&&!c.undoOf)','s.changes.find(c=>c.id===changeId)',['core']],
 ['video-negative-measurement','evidence.mjs','verdict.measured_units>=0&&','',['video']],
 ['video-reason-type',"evidence.mjs","&&typeof verdict.reason==='string'",'',['video']],
 ['preview-result-version','public/app.js','&&r.proposalVersion===p.version','',['browser-app']],
 ['poll-stale-state','public/app.js','Date.now()-lastRefreshAt>=30000','false',['browser-app']],
 ['voice-request-progress','voice.mjs',"if(!s.closed&&epoch===s.epoch&&!this.store.getRuntime(s.installationId).turn?.cancelled)","if(false)",['voice']],
 ['chat-whitespace','public/app.js',"const message=$('message').value.trim();","const message=$('message').value;",['browser-app']],
 ['voice-timeout-explanation','public/app.js',"duration_limit:'Your call ended because it reached the five-minute limit. You can start another conversation.'","duration_limit:'Call ended.'",['browser-app']],
 ['native-preview-version','public/app.js','p.id,p.version,b.dataset.testTask','p.id,p.version+1,b.dataset.testTask',['browser-app']],
 ['native-apply-notification','public/app.js','state.appliedRevision<state.revision','false',['browser-app']],
 ['selected-card-context','server.mjs','store.context(install,body.selectedCardId)','store.context(install,null)',['http']],
 ['uploaded-history','store.mjs','events:clone((body.events||[]).slice(-100))','events:[]',['http']],
 ['voice-media-cleanup','public/voice-client.js','tracks.getTracks().forEach(t=>t.stop());','void tracks;',['voice-client']],
 ['provider-incomplete','agent.mjs',"fail(r.status==='completed'","fail(true",['core','assistant-settings']],
 ['approval-target','agent.mjs','ctx.approvalTarget?.id===args.proposalId&&ctx.approvalTarget?.version===args.version','true',['core']],
 ['history-routing','agent.mjs',"args.topic==='history'","false",['app-docs']],
 ['gps-speed','evidence.mjs','(measured??inferred)>bands.maximum','false',['gps-rules','gps-parity']],
 ['gps-sprint-position','evidence.mjs','inferred<bands.minimumPosition','false',['gps-rules']],
 ['gps-mock','evidence.mjs','!p.isMock&&!p.mock','true',['gps-rules']],
 ['gps-noise','evidence.mjs','if(metres<noiseFloor)return;','if(metres<noiseFloor){this.anchor=p;return;}',['gps-rules']],
 ['gps-gap','evidence.mjs','if(seconds>15)','if(seconds>150)',['gps-rules']],
 ['gps-reward-double','evidence.mjs','this.total+=metres','this.total+=metres*2',['gps-rules']],
 ['review-stale-revision','public/app.js','expectedRevision:editBaseRevision,title','expectedRevision:state.revision,title',['browser-app']],
 ['preview-reward-rate','public/app.js','whole*s.minutesPerCredit','whole*3',['browser-app']],
 ['stream-size-limit','public/live-updates.js','buffer.length > 1000000','buffer.length > 2000000',['live-updates']],
 ['audio-note-frequency','public/voice-cue.js','[[0,660],[.11,880]]','[[0,440],[.11,880]]',['voice-cue']],
 ['voice-start-failure-cleanup','voice.mjs','async failedCreation(runtime,reservation,created){runtime.voice=null;','async failedCreation(runtime,reservation,created){',['voice']],
];
const inventory=spawnSync('python3',['-c',"import sys,json;sys.path.insert(0,'quality');from source_manifest import source_manifest;print(json.dumps(source_manifest()))"],{cwd:root,encoding:'utf8'});
if(inventory.status!==0)throw Error(inventory.stderr);
const inputsSha256=JSON.parse(inventory.stdout);
const results=[];
for(const [name,file,before,after,tests]of specs){
 if(process.argv.length>2&&!process.argv.slice(2).includes(name))continue;
 const temporary=await fs.mkdtemp(path.join(os.tmpdir(),'friction-mutation-'));
 try{
  const backend=path.join(temporary,'backend-v1');
  await fs.cp(path.join(root,'backend-v1'),backend,{recursive:true,filter:p=>!['node_modules','data','.env'].includes(path.basename(p))});
  await fs.symlink(path.join(root,'backend-v1/node_modules'),path.join(backend,'node_modules'));
  for(const doc of ['AGENTS.md','AGENT_CAPABILITIES.md'])await fs.copyFile(path.join(root,doc),path.join(temporary,doc));
  const source=await fs.readFile(path.join(backend,file),'utf8');
  if(!source.includes(before))throw Error('Mutation anchor missing: '+name);
  // Replace the named first contract occurrence; other routes remain unchanged.
  await fs.writeFile(path.join(backend,file),source.replace(before,after));
  const run=spawnSync(process.execPath,['--test',...tests.map(t=>'test/'+t+'.test.mjs')],{cwd:backend,env:{...process.env,OPENAI_API_KEY:'',OPENROUTER_API_KEY:'',PUBLIC_BASE_URL:''},encoding:'utf8',timeout:30000,maxBuffer:10*1024*1024});
  const output=(run.stdout||'')+(run.stderr||'')+(run.error?.stack||'');
  const log=path.join(logs,name+'.log');await fs.writeFile(log,output);
  const status=run.error?.code==='ETIMEDOUT'?'timed-out':run.error?'errored':run.status===0?'surviving':/ERR_ASSERTION|AssertionError/.test(output)?'detected':'errored';
  const original=await fs.readFile(path.join(root,'backend-v1',file),'utf8');
  results.push({name,file,tests,status,exit:run.status,sourceUnchanged:source===original,sourceSha256:crypto.createHash('sha256').update(source).digest('hex'),log:path.relative(root,log)});
  console.log(results.at(-1));
 }finally{await fs.rm(temporary,{recursive:true,force:true});}
 await fs.writeFile(path.join(root,'quality/metrics',runId+'.json'),JSON.stringify({scope:'Explicit GPS, configuration, recording, review, preview, stream, audio and voice lifecycle mutations only',inputsSha256,mutations:results},null,2)+'\n');
}
