import fs from 'node:fs/promises';import WebSocket from 'ws';
const files=process.argv.slice(2);if(files.length!==2)throw Error('Provide request and approval PCM files.');
const clips=await Promise.all(files.map(f=>fs.readFile(f))),events=[];let index=0,offset=-24000,timer,deadline,started,lastDone=null,doneCount=0,closing=false;
const ws=new WebSocket('ws://127.0.0.1:4317/voice'),observer=new WebSocket('ws://127.0.0.1:4317/events');
const stop=()=>{if(closing)return;closing=true;clearInterval(timer);if(ws.readyState===1)ws.send(JSON.stringify({type:'session.close'}));};
observer.on('message',raw=>{const e=JSON.parse(raw);if(e.type==='event'&&e.event.kind==='done'){doneCount++;lastDone=Date.now();console.log('FUNCTION RESULT',e.event.title);}if(e.type==='event'&&e.event.kind==='error')console.log('APP ERROR',e.event.detail);});
ws.on('open',()=>ws.send(JSON.stringify({type:'app.context',selectedTask:'walk'})));
ws.on('message',raw=>{const e=JSON.parse(raw);if(e.type!=='session.output_audio.delta')events.push(e);
 if(e.type==='session.started'){started=Date.now();console.log('VOICE CONNECTED');timer=setInterval(()=>{
   if(lastDone&&Date.now()-lastDone>10000){if(doneCount===1&&index===0){index=1;offset=0;lastDone=null;console.log('SENDING SPOKEN APPROVAL');}else if(doneCount>=2){stop();return;}}
   const pcm=clips[index],chunk=offset<0?Buffer.alloc(960):offset<pcm.length?pcm.subarray(offset,offset+960):Buffer.alloc(960);offset+=960;
   if(ws.readyState===1)ws.send(JSON.stringify({type:'session.input_audio.append',audio:chunk.toString('base64')}));
  },20);deadline=setTimeout(stop,105000);
 }
 if(e.type.includes('transcript.delta'))console.log(e.type.includes('input')?'YOU':'PULSE',e.delta);
 if(e.type==='session.delegation.created')console.log('DELEGATION',e.delegation.id);
 if(e.type==='error')console.log('VOICE ERROR',JSON.stringify(e));
 if(e.type==='session.closed'){console.log('FINAL USAGE',JSON.stringify(e.usage));ws.close();}
});
ws.on('close',async()=>{clearInterval(timer);clearTimeout(deadline);observer.close();await fs.writeFile('results/functions-v2/voice-events.json',JSON.stringify(events,null,2));console.log('VOICE FINISHED',{doneCount,wallSeconds:started?(Date.now()-started)/1000:0});if(doneCount<2)process.exitCode=1;});
ws.on('error',e=>console.error(e.message));
setTimeout(()=>{stop();setTimeout(()=>ws.terminate(),15000).unref();},125000).unref();
