import {startLiveUpdates} from './live-updates.js';
import {connectVoice} from './voice-client.js';
import {createVoiceCue} from './voice-cue.js';
const voiceCue=createVoiceCue();
const $=id=>document.getElementById(id),esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const labels={waitSeconds:'Waiting time',minutesPerCredit:'Minutes per credit',sessionMinutes:'Session lengths',dailyBudgetMinutes:'Daily allowance',dayReset:'Daily reset',youtubeCredits:'YouTube credits',emergencyMinutes:'Emergency minutes',creditName:'Credit name',unlockLabel:'Unlock button',youtubeUnlockLabel:'YouTube button',emergencyUnlockLabel:'Emergency button'};
let identity,state,voice,voiceStarting=false,voiceConnected=false,voiceGeneration=0,view='talk',lastRender='',lastMessages='',cardIndex=0,lastNativeRevision=-1,lastNativeAt=0,editing=null,editBase=null,editBaseRevision=null,submitting=false,lastVoiceRole='',lastPresentedReview='';
function error(e){const target=$('editor').open?$('editorError'):$('alert');target.textContent=e.message||String(e);target.hidden=false;}
function clearError(){$('alert').hidden=true;$('editorError').hidden=true;}
export async function api(route,body){const res=await fetch('/api/v1'+route,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+identity.token,...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});const data=await res.json();if(!res.ok)throw Error(data.error||'The request did not finish.');return data;}
async function action(fn){try{clearError();await fn();await refresh();}catch(e){error(e);lastRender='';if(state)render();}}
function value(v,key){if(v===null)return'Not set';if(Array.isArray(v))return v.join(' / ')+' min';if(key==='waitSeconds')return v+' sec';if(['minutesPerCredit','dailyBudgetMinutes','emergencyMinutes'].includes(key))return v+' min';return String(v);}
function currentProposal(){return state?.proposals?.at(-1);}
function desiredConfig(){const config=structuredClone(state.config),p=currentProposal();if(p?.status==='pending')for(const c of p.cards){if(c.kind==='setting')config.settings[c.key]=structuredClone(c.after);else{const i=config.tasks.findIndex(t=>t.id===c.key);if(c.after===null){if(i>=0)config.tasks.splice(i,1);}else if(i>=0)config.tasks[i]=structuredClone(c.after);else config.tasks.push(structuredClone(c.after));}}return config;}
let refreshJob=null,refreshAgain=false,updates=null,pollTimer=null,updateTimer=null,lastRefreshAt=0;
function refresh(){
 if(refreshJob){refreshAgain=true;return refreshJob;}
 refreshJob=(async()=>{do{refreshAgain=false;try{state=await api('/state');lastRefreshAt=Date.now();$('connection').textContent='Connected';$('connection').className='connection online';render();}catch(e){$('connection').textContent='Offline';$('connection').className='connection';$('application').textContent='Your phone keeps its last applied setup.';throw e;}}while(refreshAgain);})().finally(()=>{refreshJob=null;});return refreshJob;
}
function startUpdates(){
 if(updates||!identity)return;
 updates=startLiveUpdates({url:'/api/v1/events',headers:{Authorization:'Bearer '+identity.token},onUpdate:()=>{clearTimeout(updateTimer);updateTimer=setTimeout(()=>refresh().catch(()=>{}),16);}});
 pollTimer=setInterval(()=>{if(!updates?.isConnected()||Date.now()-lastRefreshAt>=30000)refresh().catch(()=>{});},5000);
}
function stopUpdates(){updates?.stop();updates=null;clearInterval(pollTimer);clearTimeout(updateTimer);}
window.addEventListener('pageshow',()=>{if(identity){startUpdates();refresh().catch(()=>{});}});
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'&&identity){startUpdates();refresh().catch(()=>{});}});
function renderApplicationStatus(){
 $('application').textContent=state.applicationStatus==='applied'?'Applied on your phone · revision '+state.revision:window.FrictionNative?'Saved · waiting for your phone to apply revision '+state.revision:'Browser preview · changes wait for a connected Android installation';
 if(window.FrictionNative?.configChanged&&state.appliedRevision<state.revision&&(lastNativeRevision!==state.revision||Date.now()-lastNativeAt>5000)){
  lastNativeRevision=state.revision;lastNativeAt=Date.now();try{window.FrictionNative.configChanged();}catch{}
 }
}
function followNewMessages(){
 if(state.messages.length&&view==='talk'&&!$('editor').open&&!$('previewDialog').open){
  requestAnimationFrame(()=>$('messages').querySelector('.message:last-of-type')?.scrollIntoView({block:'end',behavior:'smooth'}));
 }
}
function renderConversation(p){
 const key=JSON.stringify({proposals:state.proposals,changes:state.changes,appliedRevision:state.appliedRevision,previewResults:state.previewResults});
 const messageKey=JSON.stringify(state.messages),newMessages=messageKey!==lastMessages,changed=key!==lastRender;
 if(!changed&&!newMessages)return;
 lastMessages=messageKey;lastRender=key;renderTimeline();
 if(changed){renderReview(p);renderSetup();}
 if(newMessages)followNewMessages();
}
function render(){
 renderAssistantSettings();renderApplicationStatus();
 const p=currentProposal();renderConversation(p);
 $('working').hidden=!state.busy;$('workingText').textContent=state.cancelling?'Cancelled. Finishing the current response…':'Putting your changes together…';
 const hasConversation=state.messages.length>0||Boolean(p);
 document.querySelector('.intro').hidden=hasConversation;
 document.querySelector('.voice-panel').hidden=hasConversation;
 $('send').disabled=submitting; // Cancel and review confirmation remain available during voice/model work.
}
// Reviews stay at their creation point, so follow-up replies appear below them.
function renderTimeline(){
 const review=$('review');review.remove();
 const expanded=new Set([...document.querySelectorAll('[data-history-id][open]')].map(d=>d.dataset.historyId));
 const events=[...state.messages.map(m=>({at:m.at,rank:m.role==='user'?0:2,message:m})),...state.proposals.map(p=>({at:p.createdAt,rank:1,proposal:p}))].sort((a,b)=>a.at-b.at||a.rank-b.rank);
 $('messages').innerHTML=events.map(e=>e.message?`<article class="message ${e.message.role==='user'?'user':'assistant'}"><span class="role">${e.message.role==='user'?'YOU':'FRICTION'}</span>${esc(e.message.text)}</article>`:e.proposal.id===currentProposal()?.id?'<div id="reviewSlot"></div>':historyReview(e.proposal,expanded.has(e.proposal.id))).join('');
 const slot=$('reviewSlot');if(slot)slot.replaceWith(review);else $('conversation').append(review);
 for(const d of document.querySelectorAll('[data-history-id]'))for(const b of d.querySelectorAll('[data-undo-request]'))b.onclick=()=>action(()=>api('/cancel',{proposalId:d.dataset.historyId,operationId:crypto.randomUUID()}));
}
function reviewStatus(p){const active=state.appliedRevision>=((state.changes||[]).find(c=>c.id===p.changeId)?.revision??Infinity);return p.status==='undone'?'UNDONE':p.status==='cancelled'?'CANCELLED':p.status==='saved'?(active?'APPLIED ON YOUR PHONE':'SAVED · WAITING FOR YOUR PHONE'):'REVIEW BEFORE APPLYING';}
function canUndo(p){return p.status==='saved'&&p.changeId===state.undoableChangeId;}
function historyReview(p,open){return `<details class="history-review" data-history-id="${esc(p.id)}" ${open?'open':''}><summary><span><span class="eyebrow">${reviewStatus(p)}</span><strong>${esc(p.title)}</strong><small>View changes${canUndo(p)?' &amp; undo':''}</small></span><span class="disclosure" aria-hidden="true">⌄</span></summary><div class="history-cards">${reviewCards(p)}</div>${canUndo(p)?'<button class="secondary wide" data-undo-request>Undo this request</button>':''}</details>`;}
let reviewState='';
function renderReview(p){$('review').hidden=!p;if(!p)return;
 const status=reviewStatus(p);$('reviewTitle').textContent=p.title;$('reviewStatus').textContent=status;
 $('reviewSummaryStatus').textContent=status;$('reviewSummaryTitle').textContent=p.title;
 $('reviewDetails').classList.toggle('pending',p.status==='pending');
 const next=p.id+':'+p.status;if(reviewState!==next){$('reviewDetails').open=p.status==='pending';reviewState=next;}
 $('reviewDetails').querySelector('small').textContent=canUndo(p)?'View changes & undo':'View changes';
 $('cards').innerHTML=reviewCards(p);
 renderReviewActions(p);
 $('applyChanges')?.addEventListener('click',()=>action(async()=>{const b=$('applyChanges');b.disabled=true;b.textContent='Applying…';await api(`/proposals/${p.id}/confirm`,{version:p.version,operationId:crypto.randomUUID()});}));
 $('discardChanges')?.addEventListener('click',()=>action(()=>api('/cancel',{proposalId:p.id,operationId:crypto.randomUUID()})));$('undoChanges')?.addEventListener('click',()=>action(()=>api('/cancel',{proposalId:p.id,operationId:crypto.randomUUID()})));
 bindReviewCards(p);
 cardIndex=Math.min(cardIndex,Math.max(0,p.cards.length-1));$('dots').innerHTML=p.cards.map((_,i)=>`<span class="dot ${i===cardIndex?'active':''}"></span>`).join('');observeCardSizes();updateCardPosition();requestAnimationFrame(()=>{const c=$('cards').children[cardIndex];if(c)$('cards').scrollLeft=c.offsetLeft-$('cards').firstElementChild.offsetLeft;fitSelectedCard();presentPendingReview(p);});
}
function renderReviewActions(p){
 const saved=p.status==='saved',undone=p.status==='undone';
 $('reviewActions').innerHTML=p.status==='pending'?`<button class="primary wide" id="applyChanges">Apply ${p.cards.length===1?'change':`all ${p.cards.length} changes`}</button><button class="text-button" id="discardChanges">Cancel request</button><span class="status-note">You can also confirm by voice. Nothing changes until you approve.</span>`:canUndo(p)?'<button class="secondary wide" id="undoChanges">Undo this request</button>':`<span class="status-note">${undone?'Previous values restored. Android will confirm when active.':saved?'Only the latest saved action can be undone.':'These changes were discarded.'}</span>`;
}
function bindReviewCards(p){
 for(const b of $('review').querySelectorAll('[data-preview]'))b.onclick=()=>openPreview(p.cards.find(c=>c.id===b.dataset.preview));
 for(const b of $('review').querySelectorAll('[data-test-task]'))b.onclick=()=>action(async()=>{if(!window.FrictionNative?.testTask)throw Error('Open this review in your Android app to make a test recording. It will verify the task without earning or spending rewards.');window.FrictionNative.testTask(p.id,p.version,b.dataset.testTask);});
 for(const b of $('review').querySelectorAll('[data-remove]'))b.onclick=()=>action(()=>api(`/proposals/${p.id}/remove`,{version:p.version,cardId:b.dataset.remove}));
 for(const b of $('review').querySelectorAll('[data-adjust]'))b.onclick=()=>{const c=p.cards.find(c=>c.id===b.dataset.adjust);c.kind==='setting'?openSettings(c.key):openTask(c.key);};
}
const taskReviewFields={name:'Name',verifier:'Verification',metric:'Measurement',unitsPerCredit:'Units per credit',correctionMinimum:'Minimum for correction',instructions:'What counts',paused:'Paused',gpsMode:'GPS activity'};
function taskReviewValue(value,missing=''){return value===undefined?missing:value===null?'—':value;}
function taskReviewField(before,after,isNew,key,label){
 if(JSON.stringify(before[key])===JSON.stringify(after[key]))return '';
 if(key==='gpsMode'&&after[key]===null)return '';
 if(key==='paused'&&!after[key]&&isNew)return '';
 const oldValue=taskReviewValue(before[key],'New'),newValue=taskReviewValue(after[key]);
 return `<div class="change-row"><label>${label}</label><div class="before">${esc(oldValue)}</div><span class="arrow">→</span><div class="after">${esc(newValue)}</div></div>`;
}
function reviewCardRows(card){
 if(card.kind==='setting')return `<div class="change-row primary-value"><div class="before">${esc(value(card.before,card.key))}</div><span class="arrow">→</span><div class="after">${esc(value(card.after,card.key))}</div></div>`;
 if(card.after===null)return '<p class="muted">Remove this task from your available activities. Its history stays.</p>';
 const before=card.before||{},after=card.after||{};
 return Object.entries(taskReviewFields).map(([key,label])=>taskReviewField(before,after,!card.before,key,label)).join('');
}
function reviewCardActions(p,card){
 if(p.status!=='pending')return '';
 const recording=card.kind==='task'&&card.after?`<button data-test-task="${esc(card.key)}">Test recording</button>`:'';
 return `<button data-preview="${esc(card.id)}">Try it</button>${recording}<button data-adjust="${esc(card.id)}">Adjust</button><button data-remove="${esc(card.id)}">Remove</button>`;
}
function reviewCard(p,original,index){
 const change=state.changes?.find(c=>c.id===p.changeId),cardUndone=change?.undoneCardIds?.includes(original.id);
 const card=cardUndone?{...original,before:original.after,after:original.before}:original;
 return `<article class="change-card ${p.cards.length===1?'single':''} ${p.status==='saved'||p.status==='undone'?'completed':''} ${p.status==='cancelled'||cardUndone?'cancelled':''}" data-card-id="${esc(card.id)}"><p class="eyebrow">${card.kind==='task'?'TASK':'SETTING'} · ${index+1} OF ${p.cards.length}${cardUndone?' · UNDONE':''}</p><h3>${esc(card.title)}</h3>${reviewCardRows(card)}${testResult(p,card)}<div class="card-actions">${reviewCardActions(p,card)}</div></article>`;
}
function reviewCards(p){return p.cards.map((card,index)=>reviewCard(p,card,index)).join('');}
function presentPendingReview(p=currentProposal()){if(!p)return;const key=p.id+':'+p.version;if(p.status==='pending'&&lastPresentedReview!==key&&view==='talk'&&!$('editor').open&&!$('previewDialog').open){lastPresentedReview=key;$('review').scrollIntoView({block:'start',behavior:'smooth'});}}
// Cards keep their natural heights. Clip taller neighbours until selected, then
// let the page scroll vertically through the complete card and its actions.
function fitSelectedCard(){const rail=$('cards'),card=rail.children[cardIndex];if(!card||!card.getBoundingClientRect().height)return;const css=getComputedStyle(rail),height=Math.ceil(card.getBoundingClientRect().height+parseFloat(css.paddingTop)+parseFloat(css.paddingBottom));if(rail.style.height!==height+'px')rail.style.height=height+'px';}
let cardSizeFrame;const cardSizes=new ResizeObserver(()=>{cancelAnimationFrame(cardSizeFrame);cardSizeFrame=requestAnimationFrame(fitSelectedCard);});
function observeCardSizes(){cardSizes.disconnect();cardSizes.observe($('cards'));for(const card of $('cards').children)cardSizes.observe(card);}
function updateCardPosition(){const p=currentProposal();if(!p)return;$('cardPosition').textContent=p.cards.length?`${cardIndex+1} / ${p.cards.length}`:'';Array.from($('dots').children).forEach((d,i)=>d.classList.toggle('active',i===cardIndex));fitSelectedCard();}
let scrollTimer;$('cards').onscroll=()=>{clearTimeout(scrollTimer);scrollTimer=setTimeout(()=>{const cards=Array.from($('cards').children);if(!cards.length)return;cardIndex=cards.reduce((best,c,i)=>Math.abs(c.offsetLeft-cards[0].offsetLeft-$('cards').scrollLeft)<Math.abs(cards[best].offsetLeft-cards[0].offsetLeft-$('cards').scrollLeft)?i:best,0);updateCardPosition();api('/context',{selectedCardId:currentProposal().cards[cardIndex].id}).catch(error);},180);};
function moveCard(delta){const children=$('cards').children;const next=Math.min(children.length-1,Math.max(0,cardIndex+delta));if(children[next])$('cards').scrollTo({left:children[next].offsetLeft-children[0].offsetLeft,behavior:'smooth'});}
$('reviewDetails').addEventListener('toggle',()=>{if($('reviewDetails').open)requestAnimationFrame(fitSelectedCard);});
$('previousCard').onclick=()=>moveCard(-1);$('nextCard').onclick=()=>moveCard(1);
function renderSetup(){if(!state)return;$('settingsList').innerHTML=Object.entries(labels).map(([k,label])=>`<div class="setting-line"><span>${label}</span><strong>${esc(value(state.config.settings[k],k))}</strong></div>`).join('');$('tasksList').innerHTML=state.config.tasks.map(t=>`<article class="task"><span class="task-icon">${t.verifier==='gps'?'↗':'◉'}</span><div class="task-detail"><strong>${esc(t.name)}</strong><small>${t.paused?'Paused · ':''}${t.unitsPerCredit} ${t.metric} / credit · ${t.verifier==='gps'?'GPS '+t.gpsMode:'Video'}</small></div><button data-edit-task="${esc(t.id)}">Edit</button></article>`).join('');for(const b of document.querySelectorAll('[data-edit-task]'))b.onclick=()=>openTask(b.dataset.editTask);}
function testResult(p,card){const r=state.previewResults?.findLast(r=>r.proposalId===p.id&&r.proposalVersion===p.version&&r.taskId===card.key);return r?`<aside class="test-result"><p class="eyebrow">TEST RECORDING · NO REWARD</p><strong>${esc(r.measuredUnits)} ${esc(r.metric)} measured</strong><p>${esc(r.reason)}</p></aside>`:'';}
let previewTimer=null;
function stopPreview(){clearInterval(previewTimer);previewTimer=null;$('previewDialog').close();}
$('closePreview').onclick=stopPreview;$('returnToReview').onclick=stopPreview;$('previewDialog').addEventListener('cancel',()=>{clearInterval(previewTimer);previewTimer=null;});
function previewTask(card,s,content){const t=card.after;if(!t){content.innerHTML='<p>This task would be removed from available activities. Its existing history and earned rewards stay.</p>';}
  else{content.innerHTML=`<p class="muted">Example activity progress. Your real progress stays unchanged.</p><label class="field"><span>${esc(t.metric)} completed</span><input id="previewUnits" type="number" min="0" max="100000" step="${t.metric==='repetitions'?1:0.1}" value="${t.unitsPerCredit}"></label><div id="previewOutcome" class="preview-outcome"></div><p>${esc(t.instructions)}</p><p class="muted">A real recording uses ${t.verifier==='gps'?'GPS '+esc(t.gpsMode):'video verification'}; the Test recording button checks your definition without earning rewards.</p>`;const update=()=>{const n=Math.max(0,Number($('previewUnits').value)||0),credits=n/t.unitsPerCredit,whole=Math.floor(credits),part=Math.round((credits-whole)*100);$('previewOutcome').textContent=`Example: ${whole} ${s.creditName}, ${whole*s.minutesPerCredit} minutes${part?`, plus ${part}% toward another credit`:''}.`;};$('previewUnits').oninput=update;update();}

}
function previewWait(card,s,content){content.innerHTML=`<p>Try the proposed ${s.waitSeconds}-second pause before an unlock. This timer opens nothing and spends no rewards.</p><div id="previewCountdown" class="preview-countdown">${s.waitSeconds}</div><button id="startPreviewWait" class="primary wide">Try the wait</button><p id="previewOutcome" class="muted"></p>`;$('startPreviewWait').onclick=()=>{clearInterval(previewTimer);const finish=Date.now()+s.waitSeconds*1000;$('startPreviewWait').disabled=true;const tick=()=>{const remaining=Math.max(0,(finish-Date.now())/1000);$('previewCountdown').textContent=remaining>0?remaining.toFixed(1)+'s':'Ready';if(!remaining){clearInterval(previewTimer);$('startPreviewWait').disabled=false;$('startPreviewWait').textContent='Try again';$('previewOutcome').textContent='The example unlock is ready. Your apps and balances are unchanged.';}};previewTimer=setInterval(tick,100);tick();};

}
function previewSessions(card,s,content){content.innerHTML=`<p>Choose an example session. The actual session starts only when you unlock through your phone.</p><div class="suggestions">${s.sessionMinutes.map(n=>`<button data-preview-session="${n}">${n} minutes</button>`).join('')}</div><p id="previewOutcome" class="preview-outcome">Choose a length to see its effect.</p>`;for(const b of content.querySelectorAll('[data-preview-session]'))b.onclick=()=>{$('previewOutcome').textContent=`Example: ${b.dataset.previewSession} browsing minutes, after a ${s.waitSeconds}-second wait. No real session was started.`;};

}
function previewRewards(card,s,content){const needed=card.key==='youtubeCredits'?s.youtubeCredits:1;content.innerHTML=`<label class="field"><span>Example ${esc(s.creditName)} earned</span><input id="previewCredits" type="number" min="0" max="100" step="1" value="${needed}"></label><div id="previewOutcome" class="preview-outcome"></div><p class="muted">These are example numbers. Existing earned rewards keep their original value.</p>`;const update=()=>{const n=Math.max(0,Math.floor(Number($('previewCredits').value)||0));$('previewOutcome').textContent=card.key==='youtubeCredits'?(n>=s.youtubeCredits?'Example: YouTube is available for this daily period.':`Example: earn ${s.youtubeCredits-n} more ${s.creditName} to make YouTube available.`):`Example: ${n} × ${s.minutesPerCredit} = ${n*s.minutesPerCredit} browsing minutes.`;};$('previewCredits').oninput=update;update();

}
function previewAllowance(card,s,content){content.innerHTML=`<div class="preview-countdown">${s.dailyBudgetMinutes} min</div><p>At the next daily reset (${esc(s.dayReset)}), your browsing balance becomes ${s.dailyBudgetMinutes} minutes. Unused time does not carry over. Tasks can earn extra minutes.</p><p class="muted">Saving this setting does not change your current balance.</p>`;

}
function previewReset(card,s,content){content.innerHTML=`<div class="preview-countdown">${esc(s[card.key])}</div><p>At this daily boundary, browsing time becomes ${s.dailyBudgetMinutes} minutes, and credit progress and daily unlock eligibility reset.</p><p class="muted">Uses your phone’s local time zone. Changing a reset cannot resurrect expired rewards. This preview changes no clock or balance.</p>`;

}
function previewEmergency(card,s,content){content.innerHTML=`<div class="preview-countdown">${s.emergencyMinutes}<small> minutes</small></div><p>One example emergency unlock lasts this long. It becomes available after the first credit of the day.</p><button id="exampleEmergency" class="secondary wide">${esc(s.emergencyUnlockLabel)}</button><p id="previewOutcome" class="muted">This example will not start an unlock.</p>`;$('exampleEmergency').onclick=()=>{$('previewOutcome').textContent=`Preview only: a ${s.emergencyMinutes}-minute emergency session would start. Your real emergency unlock stays available.`;};

}
function previewLabel(card,s,content){content.innerHTML=`<p>Here is the proposed label in the app:</p><button class="primary wide" id="exampleLabel">${esc(s[card.key])}</button><p id="previewOutcome" class="muted">This is a visual example.</p>`;$('exampleLabel').onclick=()=>{$('previewOutcome').textContent='Preview only. No unlock or reward was changed.';};
}
const settingPreviews={waitSeconds:previewWait,sessionMinutes:previewSessions,minutesPerCredit:previewRewards,youtubeCredits:previewRewards,dailyBudgetMinutes:previewAllowance,dayReset:previewReset,emergencyMinutes:previewEmergency};
function openPreview(card){
 clearInterval(previewTimer);previewTimer=null;
 const settings=desiredConfig().settings;$('previewTitle').textContent=card.title;
 const preview=card.kind==='task'?previewTask:settingPreviews[card.key]||previewLabel;
 preview(card,settings,$('previewContent'));$('previewDialog').showModal();
}
function selectView(v){view=v;$('conversation').hidden=v!=='talk';$('setup').hidden=v!=='setup';$('talkTab').classList.toggle('active',v==='talk');$('setupTab').classList.toggle('active',v==='setup');$('composer').hidden=v==='setup';window.scrollTo({top:0,behavior:'smooth'});if(v==='talk')requestAnimationFrame(()=>presentPendingReview());}
$('talkTab').onclick=()=>selectView('talk');$('setupTab').onclick=()=>selectView('setup');$('back').onclick=()=>window.FrictionNative?.close?.();
const field=(name,label,val,type='text',hint='')=>`<label class="field"><span>${esc(label)}</span><input name="${name}" type="${type}" value="${esc(val)}" ${name==='dailyBudgetMinutes'?'min="0" max="30" step="1" ':''}required>${hint?`<small>${esc(hint)}</small>`:''}</label>`;
function openSettings(key=null){clearError();editing={kind:'settings',key};editBase=desiredConfig();editBaseRevision=state.revision;$('editorTitle').textContent=key?labels[key]:'Adjust your settings';$('editFields').innerHTML=Object.entries(labels).filter(([k])=>!key||key===k).map(([k,label])=>field(k,label,Array.isArray(editBase.settings[k])?editBase.settings[k].join(', '):editBase.settings[k],['dayReset'].includes(k)?'time':['waitSeconds','minutesPerCredit','dailyBudgetMinutes','youtubeCredits','emergencyMinutes'].includes(k)?'number':'text',k==='sessionMinutes'?'Three minute values separated by commas, such as 3, 5, 10.':k==='dailyBudgetMinutes'?'0–30 minutes, granted at the next daily reset. Your current balance stays the same.':k==='minutesPerCredit'?'Applies to new earnings. Existing rewards keep their value.':'')).join('');$('editor').showModal();}
function taskMethodFields(task){return field('name','Task name',task.name)+`<div class="field-row"><label class="field"><span>Verification</span><select name="verifier"><option value="video" ${task.verifier==='video'?'selected':''}>Video recording</option><option value="gps" ${task.verifier==='gps'?'selected':''}>GPS recording</option></select></label><label class="field"><span>Measurement</span><select name="metric"><option value="repetitions" ${task.metric==='repetitions'?'selected':''}>Repetitions</option><option value="seconds" ${task.metric==='seconds'?'selected':''}>Seconds</option><option value="meters" ${task.metric==='meters'?'selected':''}>Metres</option></select></label></div><label class="field" id="gpsModeField"><span>GPS activity</span><select name="gpsMode"><option value="walk" ${task.gpsMode==='walk'?'selected':''}>Walking</option><option value="sprint" ${task.gpsMode==='sprint'?'selected':''}>Sprinting</option></select></label>`;}
function taskRewardFields(task){return field('unitsPerCredit','Units needed for one credit',task.unitsPerCredit,'number')+field('correctionMinimum','Minimum verified units before correction',task.correctionMinimum,'number','A correction is available only when the recording shows at least this much activity.');}
function taskDetailFields(task){return `<label class="field"><span>What should count?</span><textarea name="instructions" required maxlength="2000">${esc(task.instructions)}</textarea></label><label class="field"><span>Availability</span><select name="paused"><option value="false" ${!task.paused?'selected':''}>Available</option><option value="true" ${task.paused?'selected':''}>Paused</option></select></label>${task.id?'<button type="button" id="removeTask" class="danger">Remove this task</button>':''}`;}
function openTask(taskId=null){clearError();editBase=desiredConfig();editBaseRevision=state.revision;const task=editBase.tasks.find(t=>t.id===taskId)||{id:null,name:'',verifier:'video',metric:'repetitions',unitsPerCredit:5,correctionMinimum:1,instructions:'',paused:false,gpsMode:null};editing={kind:'task',task:structuredClone(task)};$('editorTitle').textContent=task.id?'Edit '+task.name:'Add a task';$('editFields').innerHTML=taskMethodFields(task)+taskRewardFields(task)+taskDetailFields(task);
 const verifier=$('editForm').elements.verifier,metric=$('editForm').elements.metric;const sync=()=>{const gps=verifier.value==='gps';$('gpsModeField').hidden=!gps;for(const option of metric.options)option.disabled=gps?option.value!=='meters':option.value==='meters';if(metric.selectedOptions[0].disabled)metric.value=gps?'meters':'repetitions';};verifier.onchange=sync;sync();$('removeTask')?.addEventListener('click',()=>action(async()=>{const config=structuredClone(editBase);config.tasks=config.tasks.filter(t=>t.id!==task.id);await api('/proposals',{config,expectedRevision:editBaseRevision,title:'Remove '+task.name});$('editor').close();selectView('talk');}));$('editor').showModal();}
$('editSettings').onclick=()=>openSettings();$('addTask').onclick=()=>openTask();$('closeEditor').onclick=()=>$('editor').close();
function settingsFromForm(config,data){
 for(const[k,v]of Object.entries(data))config.settings[k]=k==='sessionMinutes'?v.split(',').map(x=>Number(x.trim())):['waitSeconds','minutesPerCredit','dailyBudgetMinutes','youtubeCredits','emergencyMinutes'].includes(k)?Number(v):v.trim();
 return 'Adjust your setup';
}
function taskFromForm(config,data){
 const task={id:editing.task.id||'task_'+crypto.randomUUID().slice(0,8),name:data.name.trim(),verifier:data.verifier,metric:data.metric,unitsPerCredit:Number(data.unitsPerCredit),correctionMinimum:Number(data.correctionMinimum),instructions:data.instructions.trim(),paused:data.paused==='true',gpsMode:data.verifier==='gps'?data.gpsMode:null};
 const i=config.tasks.findIndex(t=>t.id===task.id);if(i>=0)config.tasks[i]=task;else config.tasks.push(task);
 return (i>=0?'Update ':'Add ')+task.name;
}
$('editForm').onsubmit=e=>{e.preventDefault();action(async()=>{
 const data=Object.fromEntries(new FormData(e.target)),config=structuredClone(editBase);
 const title=editing.kind==='settings'?settingsFromForm(config,data):taskFromForm(config,data);
 await api('/proposals',{config,expectedRevision:editBaseRevision,title});$('editor').close();selectView('talk');
});};
$('chatForm').onsubmit=e=>{e.preventDefault();const message=$('message').value.trim();if(!message)return;submitting=true;$('send').disabled=true;$('message').value='';action(()=>api('/chat',{message})).finally(()=>{submitting=false;$('send').disabled=false;});};
for(const b of document.querySelectorAll('[data-prompt]'))b.onclick=()=>{$('message').value=b.dataset.prompt;$('chatForm').requestSubmit();};$('cancelWork').onclick=()=>action(()=>api('/cancel',{}));
async function stopVoice(){if(voice){const current=voice;voice=null;await current.stop();}setVoiceStatus('closed');}window.FrictionStopVoice=stopVoice;
const voiceStatusMessages={
 duration_limit:'Your call ended because it reached the five-minute limit. You can start another conversation.',
 connecting:'Getting your conversation ready',playback:'Press play to hear the conversation.',
 error:'Connection interrupted. Try again.',
};
function updateVoiceConnection(status){
 if(status==='ready'&&!voiceConnected)voiceCue.play();
 if(['closed','error','duration_limit'].includes(status))voiceCue.dispose();
 if(status==='ready')voiceConnected=true;
 else if(['connecting','closed','error','duration_limit'].includes(status))voiceConnected=false;
 if(status==='closed'||status==='duration_limit')voice=null;
}
function renderVoiceButtons(status){
 const live=voiceConnected,connecting=status==='connecting',toggle=$('composerVoice');
 const label=connecting?'Connecting voice chat':live?'End voice chat':'Start voice chat';
 toggle.disabled=connecting;toggle.classList.toggle('live',live);toggle.classList.toggle('connecting',connecting);
 toggle.setAttribute('aria-pressed',String(live));toggle.setAttribute('aria-label',label);toggle.title=label;
 $('voice').setAttribute('aria-label',live?'End voice chat':'Start voice chat');$('voice').setAttribute('aria-pressed',String(live));
 $('voice').disabled=connecting;$('voice').classList.toggle('live',live);
 $('voiceLabel').textContent=connecting?'Connecting…':live?'End voice chat':'Start voice chat';
}
function setVoiceStatus(status){
 updateVoiceConnection(status);renderVoiceButtons(status);
 $('voiceStatus').textContent=voiceStatusMessages[status]||(voiceConnected?'Listening. Tell me what you’d like to change.':'Tap to speak. You can also type below.');
 $('composerVoiceStatus').textContent=voiceConnected?'Voice chat on · Tap stop to end.':['connecting','error','duration_limit','playback'].includes(status)?$('voiceStatus').textContent:'Tap the microphone to change your setup.';
}
$('composerVoice').onclick=$('voice').onclick=()=>action(async()=>{if(voice)return stopVoice();if(voiceStarting)return;voiceStarting=true;voiceCue.prepare();const generation=++voiceGeneration;setVoiceStatus('connecting');$('voiceTranscript').textContent='';try{voice=await connectVoice({api,onStatus:status=>{if(generation===voiceGeneration)setVoiceStatus(status);},onTranscript:(delta,role)=>{const transcript=$('voiceTranscript'),newSpeaker=lastVoiceRole!==role;if(newSpeaker){transcript.textContent='';lastVoiceRole=role;}const follow=newSpeaker||transcript.getBoundingClientRect().bottom<=window.innerHeight;transcript.textContent=(transcript.textContent+delta).slice(-700);if(follow&&view==='talk'&&!$('editor').open&&!$('previewDialog').open)requestAnimationFrame(()=>transcript.scrollIntoView({block:'end',behavior:'smooth'}));}});}finally{voiceStarting=false;}});
window.addEventListener('pagehide',()=>{clearInterval(previewTimer);stopUpdates();stopVoice().catch(()=>{});});
window.FrictionNativeUpdate=()=>refresh().catch(error);
async function init(){try{if(window.FrictionNative?.bootstrap){identity=JSON.parse(window.FrictionNative.bootstrap());}else{try{identity=JSON.parse(localStorage.getItem('friction.v1.identity'));}catch{}if(!identity){const r=await fetch('/api/v1/installations',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});identity=await r.json();if(!r.ok)throw Error(identity.error);localStorage.setItem('friction.v1.identity',JSON.stringify({installationId:identity.installationId,token:identity.token}));}$('back').hidden=true;}await refresh();startUpdates();}catch(e){error(e);$('connection').textContent='Could not connect';}}

const effortLabels={none:'Non-reasoning',low:'Low',medium:'Medium',high:'High',xhigh:'Extra high',max:'Max'};
let lastAssistantSettings='';
function renderAssistantChoices(preferred){
 const model=state.assistantOptions.find(m=>m.id===$('assistantModel').value);
 if(!model)return;
 $('assistantReasoning').innerHTML=model.efforts.map(e=>`<option value="${esc(e)}">${esc(effortLabels[e]||e)}</option>`).join('');
 $('assistantReasoning').value=model.efforts.includes(preferred.reasoning)?preferred.reasoning:'low';
 $('assistantSpeed').innerHTML=model.serviceTiers.map(t=>`<option value="${esc(t)}">${t==='fast'?'Fast':'Standard'}</option>`).join('');
 $('assistantSpeed').value=model.serviceTiers.includes(preferred.serviceTier)?preferred.serviceTier:'default';
}
function renderAssistantSettings(){
 if(!state.assistantSettings||!state.assistantOptions)return;
 const key=JSON.stringify(state.assistantSettings);if(key===lastAssistantSettings)return;
 lastAssistantSettings=key;
 $('assistantModel').innerHTML=state.assistantOptions.map(m=>`<option value="${esc(m.id)}">${esc(m.label)}</option>`).join('');
 $('assistantModel').value=state.assistantSettings.model;renderAssistantChoices(state.assistantSettings);
}
$('assistantModel').addEventListener('change',()=>{renderAssistantChoices({reasoning:$('assistantReasoning').value,serviceTier:$('assistantSpeed').value});$('assistantStatus').textContent='Unsaved changes';});
for(const id of ['assistantReasoning','assistantSpeed'])$(id).addEventListener('change',()=>{$('assistantStatus').textContent='Unsaved changes';});
$('assistantForm').addEventListener('submit',async event=>{
 event.preventDefault();const button=$('saveAssistant');button.disabled=true;$('assistantStatus').textContent='Saving…';
 try{state=await api('/assistant-settings',{model:$('assistantModel').value,reasoning:$('assistantReasoning').value,serviceTier:$('assistantSpeed').value});render();$('assistantStatus').textContent='Saved. Applies to your next request.';}
 catch(e){$('assistantStatus').textContent=e.message||'Could not save. Please try again.';}
 finally{button.disabled=false;}
});

init();
