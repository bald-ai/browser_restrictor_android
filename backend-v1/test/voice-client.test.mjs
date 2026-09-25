import test from 'node:test';
import assert from 'node:assert/strict';
import {connectVoice} from '../public/voice-client.js';

test('remote timeout explains closure; manual and unrelated closures do not',async t=>{
 const originalPeer=globalThis.RTCPeerConnection,originalAudio=globalThis.Audio;
 t.after(()=>{globalThis.RTCPeerConnection=originalPeer;globalThis.Audio=originalAudio;});
 let channel,stopped;
 globalThis.Audio=class{remove(){}};
 globalThis.RTCPeerConnection=class{
  iceGatheringState='complete';localDescription={sdp:'v=0'};
  addTrack(){}createDataChannel(){return channel={close(){}};}async createOffer(){return{};}
  async setLocalDescription(){}async setRemoteDescription(){channel.onmessage({data:JSON.stringify({type:'session.started'})});}close(){}
 };
 for(const mode of ['duration_limit','user','different_session','manual']){
  const statuses=[];stopped=false;
  const api=async route=>route==='/voice/session'?{sessionId:'current',sdp:'v=0'}:route==='/state'?{lastVoiceEnd:{sessionId:mode==='different_session'?'old':'current',reason:mode==='user'?'user':'duration_limit'}}:{};
  const connection=await connectVoice({api,onStatus:s=>statuses.push(s),stream:{getTracks:()=>[{stop(){stopped=true;}}],getAudioTracks:()=>[]}});
  if(mode==='manual')await connection.stop();else channel.onmessage({data:JSON.stringify({type:'session.closed'})});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(stopped,true);assert.equal(statuses.includes('duration_limit'),mode==='duration_limit',mode);
 }
});

test('voice client forwards transcripts, retries playback by exposing controls, and releases media on failure',async t=>{
 const keys=['RTCPeerConnection','Audio','navigator','document','MediaStream'];const originals=new Map(keys.map(k=>[k,Object.getOwnPropertyDescriptor(globalThis,k)]));
 t.after(()=>{for(const[k,d]of originals)if(d)Object.defineProperty(globalThis,k,d);else delete globalThis[k];});
 const calls=[],statuses=[],transcripts=[],events=[];let peer,audio,stops=0,attached;
 const stream={getTracks:()=>[{stop:()=>stops++}],getAudioTracks:()=>[{}]};
 Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaDevices:{getUserMedia:async options=>{assert.deepEqual(options,{audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});return stream;}}}});
 globalThis.document={body:{append:a=>{attached=a;}}};globalThis.MediaStream=class{constructor(tracks){this.tracks=tracks;}};
 globalThis.Audio=class{constructor(){audio=this;}play(){return Promise.reject(Error('gesture required'));}remove(){this.removed=true;}};
 globalThis.RTCPeerConnection=class{
  constructor(){peer=this;}iceGatheringState='complete';localDescription={sdp:'v=0 fixture'};
  addTrack(track,source){assert.equal(source,stream);}createDataChannel(){return this.channel={close:()=>{this.channelClosed=true;}};}
  async createOffer(){return{};}async setLocalDescription(){}async setRemoteDescription(){this.channel.onmessage({data:'{"type":"session.started"}'});}close(){this.closed=true;}
 };
 const connection=await connectVoice({api:async(route,body)=>{calls.push({route,body});return route==='/voice/session'?{sessionId:'client',sdp:'v=0 answer'}:{};},onStatus:s=>statuses.push(s),onTranscript:(text,role)=>transcripts.push([text,role]),onEvent:e=>events.push(e)});
 for(const event of [{type:'session.output_transcript.delta',delta:'Hello'},{type:'session.input_transcript.delta',delta:'Change wait'},{type:'session.output_transcript.delta'}, {type:'session.input_transcript.delta'}])peer.channel.onmessage({data:JSON.stringify(event)});
 peer.channel.onmessage({data:'invalid json'});
 assert.deepEqual(transcripts,[['Hello','assistant'],['Change wait','user'],['','assistant'],['','user']]);assert.equal(events.length,5);
 peer.ontrack({streams:[],track:'audio-track'});await new Promise(r=>setImmediate(r));assert.equal(attached,audio);assert.equal(audio.controls,true);assert(statuses.includes('playback'));
 peer.connectionState='connected';peer.onconnectionstatechange();peer.connectionState='failed';peer.onconnectionstatechange();await new Promise(r=>setImmediate(r));
 assert.equal(stops,1);assert.equal(peer.closed,true);assert.equal(peer.channelClosed,true);assert.equal(audio.srcObject,null);assert.equal(audio.removed,true);assert(statuses.includes('error'));
 assert.deepEqual(calls.find(c=>c.route==='/voice/close').body,{sessionId:'client'});await connection.stop();assert.equal(stops,1);
});

test('voice client cleans up acquired media when offer creation fails',async t=>{
 const oldPeer=globalThis.RTCPeerConnection,oldAudio=globalThis.Audio;let stopped=0,closed=0,removed=0;
 t.after(()=>{globalThis.RTCPeerConnection=oldPeer;globalThis.Audio=oldAudio;});
 globalThis.Audio=class{remove(){removed++;}};
 globalThis.RTCPeerConnection=class{addTrack(){}createDataChannel(){return{close(){}};}async createOffer(){throw Error('offer failed');}close(){closed++;}};
 const stream={getTracks:()=>[{stop:()=>stopped++}],getAudioTracks:()=>[]};
 await assert.rejects(connectVoice({api:()=>assert.fail('no server call before offer'),stream}),/offer failed/);
 assert.equal(stopped,1);assert.equal(closed,1);assert.equal(removed,1);
});

test('voice protocol errors retain nested or top-level messages and supply a fallback',async t=>{
 const oldPeer=globalThis.RTCPeerConnection,oldAudio=globalThis.Audio;
 t.after(()=>{globalThis.RTCPeerConnection=oldPeer;globalThis.Audio=oldAudio;});
 for(const [event,message] of [[{error:{message:'nested'},message:'outer'},'nested'],[{message:'outer'},'outer'],[{},'Voice error.']]){
  let stopped=0;
  globalThis.Audio=class{remove(){}};
  globalThis.RTCPeerConnection=class{
   iceGatheringState='complete';localDescription={sdp:'v=0'};
   addTrack(){}createDataChannel(){return this.channel={close(){}};}async createOffer(){return{};}
   async setLocalDescription(){}async setRemoteDescription(){this.channel.onmessage({data:JSON.stringify({type:'error',...event})});}close(){}
  };
  const statuses=[];
  await assert.rejects(connectVoice({api:async()=>({sessionId:'test',sdp:'v=0'}),onStatus:s=>statuses.push(s),stream:{getTracks:()=>[{stop(){stopped++;}}],getAudioTracks:()=>[]}}),error=>error.message===message);
  assert.equal(stopped,1);assert.deepEqual(statuses,['error','closed']);
 }
});
