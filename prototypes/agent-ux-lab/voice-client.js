// Shared by the app and the repeatable transport check. Normal app calls use WebRTC.
export async function connectVoice({stream,selectedTask=null,transport='webrtc',onEvent=()=>{},onAudio=()=>{},onStatus=()=>{}}){
 const audioContext=new AudioContext();await audioContext.resume();const analyser=audioContext.createAnalyser();analyser.fftSize=512;
 const silentMonitor=audioContext.createGain();silentMonitor.gain.value=0;analyser.connect(silentMonitor);silentMonitor.connect(audioContext.destination);
 const samples=new Float32Array(analyser.fftSize);let peer,events,socket,processor,source,sessionId,closed=false,ready=false,outputAt=0,closeTimer,connectTimer;
 const audio=new Audio();audio.autoplay=true;audio.controls=true;audio.id='livePlayback';
 const timer=setInterval(()=>{analyser.getFloatTimeDomainData(samples);const rms=Math.sqrt(samples.reduce((n,v)=>n+v*v,0)/samples.length);if(rms>.005)onAudio(performance.now());},10);
 let connectedResolve,connectedReject;const connected=new Promise((resolve,reject)=>{connectedResolve=resolve;connectedReject=reject;});
 function cleanup(){if(closed)return;closed=true;clearInterval(timer);clearTimeout(closeTimer);clearTimeout(connectTimer);processor?.disconnect();source?.disconnect();stream.getTracks().forEach(t=>t.stop());events?.close();peer?.close();socket?.close();audio.srcObject=null;audio.remove();audioContext.close();onStatus('closed');}
 function receive(e){onEvent({...e,receivedAt:performance.now()});if(e.type==='session.started'){ready=true;clearTimeout(connectTimer);connectedResolve();onStatus('ready');}if(e.type==='session.closed')cleanup();if(e.type==='error'){const err=Error(e.error?.message||'Voice API error');if(!ready)connectedReject(err);onStatus('error',err.message);}}
 function stop(){if(closed)return;if(events?.readyState==='open')events.send(JSON.stringify({type:'session.close'}));else if(socket?.readyState===1)socket.send(JSON.stringify({type:'session.close'}));else if(sessionId)fetch('/api/voice/stop',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sessionId})}).catch(()=>{});closeTimer=setTimeout(cleanup,15000);}
 try{
  connectTimer=setTimeout(()=>connectedReject(Error('Voice connection timed out')),30000);
  if(transport==='webrtc'){
   peer=new RTCPeerConnection();peer.ontrack=e=>{audio.srcObject=new MediaStream([e.track]);source=audioContext.createMediaStreamSource(audio.srcObject);source.connect(analyser);audio.play().catch(()=>{document.body.append(audio);onStatus('playback','Press play to hear Pulse.');});};
   peer.onconnectionstatechange=()=>{if(peer.connectionState==='failed'){onStatus('error','WebRTC connection failed');stop();if(!ready)connectedReject(Error('WebRTC connection failed'));}};
   for(const track of stream.getAudioTracks())peer.addTrack(track,stream);
   events=peer.createDataChannel('oai-events');events.onmessage=({data})=>receive(JSON.parse(data));events.onclose=()=>{if(!closed){onStatus('error','Voice connection closed');cleanup();}};
   await peer.setLocalDescription(await peer.createOffer());
   if(peer.iceGatheringState!=='complete')await new Promise((resolve,reject)=>{const t=setTimeout(()=>reject(Error('ICE gathering timed out')),10000);peer.onicegatheringstatechange=()=>{if(peer.iceGatheringState==='complete'){clearTimeout(t);resolve();}};});
   const r=await fetch('/api/voice/session',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sdp:peer.localDescription.sdp,selectedTask})});const j=await r.json();if(!r.ok)throw Error(j.error);sessionId=j.session.id;
   await peer.setRemoteDescription({type:'answer',sdp:j.transport.sdp});
  }else{
   socket=new WebSocket(`ws://${location.host}/voice`);socket.onopen=()=>socket.send(JSON.stringify({type:'app.context',selectedTask}));
   socket.onmessage=({data})=>{const e=JSON.parse(data);receive(e);if(e.type==='session.started'){
    source=audioContext.createMediaStreamSource(stream);processor=audioContext.createScriptProcessor(2048,1,1);source.connect(processor);processor.connect(audioContext.destination);
    processor.onaudioprocess=ev=>{if(socket.readyState!==1)return;const input=ev.inputBuffer.getChannelData(0),ratio=audioContext.sampleRate/24000,pcm=new Int16Array(Math.floor(input.length/ratio));for(let i=0;i<pcm.length;i++)pcm[i]=Math.max(-32768,Math.min(32767,input[Math.floor(i*ratio)]*32767));const bytes=new Uint8Array(pcm.buffer);let raw='';for(const b of bytes)raw+=String.fromCharCode(b);socket.send(JSON.stringify({type:'session.input_audio.append',audio:btoa(raw)}));};
   }else if(e.type==='session.output_audio.delta'){
    const raw=atob(e.delta),bytes=Uint8Array.from(raw,c=>c.charCodeAt(0)),pcm=new Int16Array(bytes.buffer),buffer=audioContext.createBuffer(1,pcm.length,24000);for(let i=0;i<pcm.length;i++)buffer.getChannelData(0)[i]=pcm[i]/32768;const node=audioContext.createBufferSource();node.buffer=buffer;node.connect(analyser);analyser.connect(audioContext.destination);outputAt=Math.max(outputAt,audioContext.currentTime+.025);node.start(outputAt);outputAt+=buffer.duration;
   }};socket.onerror=()=>connectedReject(Error('Voice socket failed'));socket.onclose=()=>cleanup();
  }
  await connected;
  return {stop,sessionId,transport,stats:async()=>peer?Array.from((await peer.getStats()).values()).filter(x=>['inbound-rtp','outbound-rtp','candidate-pair'].includes(x.type)):[]};
 }catch(e){stop();cleanup();throw e;}
}
