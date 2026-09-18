import {connectVoice} from './voice-client.js';
let voiceText={user:'',assistant:''};
let state=null,selected=null,lastMessages='',voice=null,gateInterval=null;
const $=id=>document.getElementById(id),money=x=>'$'+Number(x||0).toFixed(4);
function el(tag,cls,text){const x=document.createElement(tag);if(cls)x.className=cls;if(text!==undefined)x.textContent=text;return x;}
function error(message){$('error').hidden=!message;$('error').textContent=message||'';}
async function post(url,data={}){const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});const j=await r.json();if(!r.ok)throw Error(j.error||'Request failed');return j;}
function render(s){state=s;
 const active=s.conversations.find(c=>c.id===s.activeConversation);if(active)$('model').value=active.model;
 $('revision').textContent='v'+s.revision;$('totalCost').textContent=money(s.totals.model);$('budgetLabel').textContent=`${s.totals.modelWithCacheWriteAllowance===s.totals.model?'Includes reported cache writes':'Up to '+money(s.totals.modelWithCacheWriteAllowance)+' with cache-write allowance'}${s.totals.unpricedTurns?` · ${s.totals.unpricedTurns} turn(s) awaiting usage`:''}`;$('voiceCost').textContent=money(s.totals.voice);$('turnCount').textContent=s.turns.length+' measured turns';
 $('settings').replaceChildren();for(const [name,val] of [['Focused wait',s.config.settings.waitSeconds+' sec'],['Per credit',s.config.settings.minutesPerCredit+' min'],['YouTube opens',s.config.settings.youtubeCredits+' credits'],['Time resets',s.config.settings.budgetReset],['Day resets',s.config.settings.dayReset],['Emergency',s.config.settings.emergencyMinutes+' min']]){const d=el('div','setting');d.append(el('span','',name),el('strong','',val));$('settings').append(d);}
 $('tasks').replaceChildren();for(const t of s.config.tasks){const b=el('button','task'+(selected===t.id?' selected':'')+(t.paused?' paused':''));b.dataset.task=t.id;const info=el('span','task-copy');info.append(el('strong','',t.name),el('small','',`${t.unitsPerCredit} ${t.metric==='repetitions'?'reps':t.metric} → 1 credit${t.paused?' · paused':''}`));b.append(el('span','task-icon',t.verifier==='gps'?'↗':'◎'),info,el('span','task-kind',t.verifier.toUpperCase()));b.onclick=()=>{selected=selected===t.id?null:t.id;render(state);};b.title=t.instructions;$('tasks').append(b);}
 $('selectedContext').hidden=!selected;$('selectedContext').textContent=selected?'In context: '+(s.config.tasks.find(t=>t.id===selected)?.name||selected):'';
 renderReviewCards(s);$('cancelRequest').hidden=!s.busy;$('working').textContent=s.cancelling?'Change cancelled. Finishing the current response…':'Looking up, preparing or checking your setup…';$('working').hidden=!s.busy;$('send').disabled=s.busy||s.voiceActive;$('model').disabled=s.busy||s.voiceActive;$('newConversation').disabled=s.busy||s.voiceActive;$('reset').disabled=s.busy||s.voiceActive;$('cleanup').disabled=s.busy||s.voiceActive;
 const c=s.conversations.find(c=>c.id===s.activeConversation)||s.conversations.at(-1);const sig=JSON.stringify(c?.messages||[]);
 if(sig!==lastMessages){lastMessages=sig;if(c?.messages.length){$('messages').replaceChildren();for(const m of c.messages){const bubble=el('div','message '+m.role);bubble.append(el('span','message-label',m.role==='user'?(m.source==='voice'?'YOU · VOICE':'YOU'):'PULSE'),document.createTextNode(m.text));$('messages').append(bubble);}$('messages').scrollTop=$('messages').scrollHeight;}else{$('messages').innerHTML='<div class="welcome"><div class="pulse-icon">✳</div><h3>Small changes.<br>Made by conversation.</h3><p>Try changing a wait, creating a task, or asking why an app is locked.</p></div>';}}
 $('activity').replaceChildren();for(const event of s.events.slice(-35).reverse())renderEvent(event,false);
 $('costRows').replaceChildren();for(const t of s.turns.slice(-8)){const row=el('div','cost-row'),info=el('div');info.append(el('span','',t.message),el('small','',`${t.model.replace('gpt-','')} · ${(t.elapsedMs/1000).toFixed(1)}s · ${t.calls?.length||0} model calls · ${t.cost?`${t.cost.inputTokens.toLocaleString()} in / ${t.cost.outputTokens.toLocaleString()} out`:'usage pending'}${t.status==='failed'?' · failed':''}`));row.append(info,el('strong','',t.cost?money(t.cost.usd):'pending'));$('costRows').append(row);}
 $('recordings').replaceChildren();for(const v of s.voices.filter(x=>x.audioUrl).slice(-3)){const label=el('div','audio-label',`AI voice reply · ${v.seconds.toFixed(0)}s session`);const a=el('audio');a.controls=true;a.src=v.audioUrl;$('recordings').append(label,a);}
}
function renderEvent(e,prepend=true){const row=el('div','event '+e.kind),info=el('div','event-content');info.append(el('span','event-time',new Date(e.time).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit'})+' · '+e.kind.toUpperCase()),el('div','event-title',e.title));if(e.detail){const d=el('details');d.append(el('summary','',e.kind==='docs'?'Read source excerpts':e.kind==='verified'?'Inspect saved result':'Details'),el('pre','',e.detail));info.append(d);}row.append(el('span','event-dot'),info);if(prepend)$('activity').prepend(row);else $('activity').append(row);}
const channel=new WebSocket(`ws://${location.host}/events`);channel.onmessage=({data})=>{const e=JSON.parse(data);if(e.type==='state')render(e.state);else if(e.type==='event'){renderEvent(e.event);if(state)state.events.push(e.event);}else if(e.type==='voice_transcript'){voiceText[e.role]+=e.delta;$('voiceInfo').textContent=(e.role==='user'?'You: ':'Pulse: ')+voiceText[e.role].slice(-250);}};
channel.onclose=()=>error('Connection to the local demo closed. Restart the server and refresh.');
async function submit(message){error('');try{if(!state.activeConversation)await post('/api/new',{model:$('model').value});await post('/api/chat',{message,selectedTask:selected});}catch(e){error(e.message);}}
$('cancelRequest').onclick=async()=>{try{await post('/api/cancel');}catch(e){error(e.message);}};
$('chatForm').onsubmit=e=>{e.preventDefault();const m=$('prompt').value.trim();if(m){$('prompt').value='';submit(m);}};
$('prompt').onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();$('chatForm').requestSubmit();}};
for(const b of document.querySelectorAll('[data-prompt]'))b.onclick=()=>{$('prompt').value=b.dataset.prompt;$('prompt').focus();};
$('newConversation').onclick=async()=>{try{await post('/api/new',{model:$('model').value});error('');}catch(e){error(e.message);}};
$('model').onchange=async()=>{if(state?.activeConversation&&!state.busy&&!state.voiceActive)try{await post('/api/new',{model:$('model').value});}catch(e){error(e.message);}};
$('reset').onclick=async()=>{try{await post('/api/reset');}catch(e){error(e.message);}};
$('cleanup').onclick=async()=>{try{await post('/api/cleanup');}catch(e){error(e.message);}};
$('tryGate').onclick=()=>{$('gateCopy').textContent=`Your current rule asks for ${state.config.settings.waitSeconds} seconds before a browsing session.`;$('gateTimer').textContent=state.config.settings.waitSeconds;$('startWait').disabled=false;$('startWait').textContent='Start focused wait';$('gate').showModal();};
$('gate').onclose=()=>clearInterval(gateInterval);
$('closeGate').onclick=()=>{clearInterval(gateInterval);$('gate').close();};
$('startWait').onclick=()=>{let n=state.config.settings.waitSeconds;$('startWait').disabled=true;clearInterval(gateInterval);const tick=()=>{$('gateTimer').textContent=n;if(n<=0){clearInterval(gateInterval);$('gateTimer').textContent='✓';$('gateCopy').textContent='Access would open now. Your saved rule is working in the preview.';}else n--;};const needsTimer=n>0;tick();if(needsTimer)gateInterval=setInterval(tick,1000);};
let voiceConnecting=false;
$('voice').onclick=async()=>{
 if(voiceConnecting)return;
 if(voice){voice.stop();return;}
 error('');voiceConnecting=true;$('voice').disabled=true;
 try{
  if(!state.activeConversation)await post('/api/new',{model:$('model').value});
  const stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,channelCount:1}});
  $('voiceInfo').textContent='Connecting direct WebRTC audio…';
  voice=await connectVoice({stream,selectedTask:selected,onStatus:(status,message)=>{
   if(status==='ready'){$('voice').classList.add('live');$('voice').innerHTML='■ <span>End voice</span>';$('voiceInfo').textContent='Listening · direct WebRTC audio';}
   if(status==='error'||status==='playback')error(message);
   if(status==='closed'){voice=null;$('voice').classList.remove('live');$('voice').innerHTML='◉ <span>Start voice</span>';$('voiceInfo').textContent='Voice ended. Usage recorded below.';}
  }});
 }catch(e){error(e.message);}finally{voiceConnecting=false;$('voice').disabled=false;}
};
window.addEventListener('pagehide',()=>voice?.stop());

const fieldNames={waitSeconds:'Waiting time (seconds)',minutesPerCredit:'Minutes per credit',budgetReset:'Browsing time reset',dayReset:'Progress reset',youtubeCredits:'Credits to open YouTube',emergencyMinutes:'Emergency minutes',name:'Task name',paused:'Paused',unitsPerCredit:'Units per credit',verifier:'Verification method',metric:'What we measure',correctionMinimum:'Minimum evidence for corrections',instructions:'What the verifier will look for'};
function renderReviewCards(s){
 const host=$('reviewCards');host.replaceChildren();
 for(const d of s.drafts||[]){
  const verified=s.changes?.find(c=>c.id===(d.status==='undone'?d.undoChangeId:d.changeId))?.verified;
  const card=el('section','review-card'+(verified?' verified':'')),top=el('div','review-heading');
  top.append(el('span','eyebrow',d.status==='cancelled'?'CANCELLED':d.status==='undone'?(verified?'✓ UNDONE AND VERIFIED':'UNDO · CHECKING'):d.status==='pending'?'REVIEW YOUR CHANGE':verified?'✓ SAVED AND VERIFIED':'SAVED · CHECKING'));
  card.append(top,el('h3','',d.kind==='new_task'?d.task.name:d.kind==='setting'?'Update your rule':d.task?.name||'Update task'));
  for(const r of d.review){const row=el('div','review-row');row.append(el('span','review-label',fieldNames[r.field]||r.field));const values=el('div','review-values');if(r.before!==null)values.append(el('span','before-value',String(d.status==='undone'&&d.kind!=='new_task'?r.after:r.before)),el('span','arrow','→'));values.append(el('strong','',String(d.status==='undone'&&d.kind!=='new_task'?r.before:r.after)));row.append(values);card.append(row);}
  if(d.kind==='new_task')card.append(el('small','',`1 credit earns ${s.config.settings.minutesPerCredit} browsing minutes. Video/GPS verification is simulated here.`));
  if(d.status==='pending'){const b=el('button','send-button confirm-change','Confirm change');b.disabled=s.busy||s.voiceActive;b.onclick=async()=>{error('');b.disabled=true;try{await post('/api/approve',{draftId:d.id});}catch(e){error(e.message);b.disabled=false;}};card.append(b,el('small','', 'Or ask for an adjustment, or approve by voice/chat.'));}
  else card.append(el('small','',d.status==='cancelled'?'Proposal discarded. No change was applied.':d.status==='undone'?(d.kind==='new_task'?'Created task removed.':'Previous values restored.'):verified?'Read back from saved settings and matched to this review card.':'Waiting for the saved values to be checked.'));
  if(['pending','active'].includes(d.status)){const cancel=el('button','text-btn',d.status==='pending'?'Cancel change':'Undo change');cancel.onclick=async()=>{try{await post('/api/cancel',{draftId:d.id,message:d.status==='pending'?'Cancel that':'Undo that'});}catch(e){error(e.message);}};card.append(cancel);}
  host.append(card);
 }
}
