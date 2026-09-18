import {connectVoice} from './voice-client.js';
const out=document.querySelector('#out');const log=x=>out.textContent+='\n'+(typeof x==='string'?x:JSON.stringify(x,null,2));
const post=async(route,body={})=>{const r=await fetch('/api/'+route,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const j=await r.json();if(!r.ok)throw Error(j.error);return j;};
async function run(transport){
 document.querySelectorAll('button').forEach(b=>b.disabled=true);out.textContent='Starting '+transport;let voice,ctx,observer,timeout,poll;const started=performance.now(),turns=[],events=[];let current,lastAudio=0,lastOutputTranscript=0,doneCount=0,closing=false;
 const result={transport,startedAt:new Date().toISOString(),model:null,reasoning:'low',turns,events};
 try{
  await post('reset');const conversationSetup=await post('new',{label:'Transport check · '+transport});result.model=conversationSetup.model;
  ctx=new AudioContext();await ctx.resume();const dest=ctx.createMediaStreamDestination();const clockSource=ctx.createConstantSource();clockSource.offset.value=0.00001;clockSource.connect(dest);clockSource.start();
  const clips=await Promise.all(['request','approval','cancel'].map(async n=>ctx.decodeAudioData(await (await fetch('/test-audio/'+n+'.wav')).arrayBuffer())));
  function play(i){current={utterance:['request','approval','cancel'][i],startMs:performance.now(),endMs:null,firstAudioMs:null,agentDoneMs:null};turns.push(current);const source=ctx.createBufferSource();source.buffer=clips[i];source.connect(dest);const samples=clips[i].getChannelData(0);let end=samples.length-1;while(end>0&&Math.abs(samples[end])<.002)end--;current.endMs=current.startMs+end/clips[i].sampleRate*1000;source.start();log('Sent '+current.utterance);}
  observer=new WebSocket(`ws://${location.host}/events`);observer.onmessage=({data})=>{const e=JSON.parse(data);if(e.type==='event'&&(e.event.kind==='done'||e.event.kind==='cancelled'&&e.event.title==='Cancelled. The previous values are restored and verified.')&&current){current.agentDoneMs=performance.now();current.reply=e.event.title;doneCount++;log('Agent result: '+e.event.title);}if(e.type==='event'&&e.event.kind==='error')log('App error: '+e.event.detail);};
  let finishResolve;const finished=new Promise(resolve=>finishResolve=resolve);
  voice=await connectVoice({transport,stream:dest.stream,onAudio:t=>{lastAudio=t;if(current&&!current.firstAudioMs&&t>=current.startMs)current.firstAudioMs=t;},onEvent:e=>{if(e.type==='session.output_transcript.delta')lastOutputTranscript=performance.now();if(e.type!=='session.output_audio.delta')events.push(e);if(e.type==='session.closed'){result.usage=e.usage;result.final=true;}},onStatus:(s,m)=>{log(m||s);if(s==='closed')finishResolve();}});
  result.connectMs=performance.now()-started;play(0);
  poll=setInterval(async()=>{if(closing)return;if(current?.agentDoneMs&&performance.now()-current.agentDoneMs>10000&&lastOutputTranscript>current.agentDoneMs&&performance.now()-lastOutputTranscript>1500&&performance.now()-lastAudio>1200){if(doneCount===1&&turns.length===1)play(1);else if(doneCount===2&&turns.length===2)play(2);else if(doneCount>=3){closing=true;result.rtcStats=await voice.stats();voice.stop();}}},100);
  timeout=setTimeout(()=>{result.timeout=true;closing=true;voice.stop();},110000);await finished;
  const state=await (await fetch('/api/state')).json();result.voice=state.voice;result.finalConfig=state.config;result.drafts=state.drafts;result.serviceTier=state.agentServiceTier;const conversation=state.activeConversation;result.agentTurns=state.turns.filter(t=>t.conversationId===conversation).map(t=>({model:t.model,reasoning:t.reasoning,serviceTiers:t.calls.map(c=>c.serviceTier),elapsedMs:t.elapsedMs,cost:t.cost?.usd,status:t.status,verifiedChanges:t.verifiedChanges}));
  result.verified=state.config.settings.waitSeconds===12&&state.drafts.some(d=>d.status==='undone')&&result.agentTurns.some(t=>t.verifiedChanges.length)&&result.final===true&&doneCount===3;
  for(const t of turns){t.replyStartAfterSpeechMs=t.firstAudioMs===null?null:Math.max(0,t.firstAudioMs-t.endMs);t.agentResultAfterSpeechMs=t.agentDoneMs===null?null:t.agentDoneMs-t.endMs;}
  await post('cancellation-result',result);log({verified:result.verified,connectMs:result.connectMs,turns,usage:result.usage});
 }catch(e){log('FAILED: '+e.message);voice?.stop();}finally{clearInterval(poll);clearTimeout(timeout);observer?.close();ctx?.close();document.querySelectorAll('button').forEach(b=>b.disabled=false);}
}
document.querySelector('#rtc').onclick=()=>run('webrtc');
