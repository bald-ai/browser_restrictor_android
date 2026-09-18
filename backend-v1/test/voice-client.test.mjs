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
