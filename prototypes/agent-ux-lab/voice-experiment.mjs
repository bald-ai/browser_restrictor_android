// Feed synthesized speech through the same PCM WebSocket route as the browser microphone.
import fs from 'node:fs/promises';
import WebSocket from 'ws';
const file=process.argv[2];if(!file)throw Error('Usage: node voice-test.mjs path-to-24khz-mono-s16le.pcm');
const pcm=await fs.readFile(file),events=[];
const ws=new WebSocket('ws://127.0.0.1:4317/voice');
let timer,deadline,offset=-24000,finishedAt=null,closed=false,lastAudio=Date.now(),started;
const stop=()=>{if(closed)return;closed=true;clearInterval(timer);ws.send(JSON.stringify({type:'session.close'}));};
const eventWs=new WebSocket('ws://127.0.0.1:4317/events');
eventWs.on('message',raw=>{const e=JSON.parse(raw);if(e.type==='event'&&['done','error'].includes(e.event.kind)){finishedAt=Date.now();console.log('AGENT',e.event.title);}});
ws.on('message',raw=>{const e=JSON.parse(raw);if(e.type!=='session.output_audio.delta')events.push(e);
 if(e.type==='session.started'){
  started=Date.now();console.log('VOICE CONNECTED',e.session.id);
  timer=setInterval(()=>{
   const chunk=offset<0?Buffer.alloc(960):offset<pcm.length?pcm.subarray(offset,offset+960):Buffer.alloc(960);offset+=960;
   ws.send(JSON.stringify({type:'session.input_audio.append',audio:chunk.toString('base64')}));
   if(finishedAt&&Date.now()-finishedAt>15000)stop();
  },20);deadline=setTimeout(stop,105000);
 }
 if(e.type==='session.output_audio.delta')lastAudio=Date.now();
 if(e.type.includes('transcript.delta'))console.log(e.type.includes('input')?'YOU':'VOICE',e.delta);
 if(e.type==='session.delegation.created')console.log('DELEGATED',e.delegation.id);
 if(e.type==='session.closed'){console.log('FINAL USAGE',JSON.stringify(e.usage));closed=true;ws.close();}
 if(e.type==='error')console.log('ERROR',JSON.stringify(e));
});
ws.on('close',async()=>{clearInterval(timer);clearTimeout(deadline);eventWs.close();await fs.writeFile('results/voice-test-events.json',JSON.stringify(events,null,2));console.log('VOICE TEST CLOSED',started?(Date.now()-started)/1000:0);});
ws.on('error',e=>console.error(e.message));
setTimeout(()=>{if(ws.readyState!==3){stop();setTimeout(()=>ws.terminate(),15000);}},125000).unref();
