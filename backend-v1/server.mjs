import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {Store} from './store.mjs';
import {Agent} from './agent.mjs';
import {Voice} from './voice.mjs';
import {AppError,fail} from './domain.mjs';
import {parseMultipart,verifyGps,verifyVideo} from './evidence.mjs';
const root=path.dirname(fileURLToPath(import.meta.url));
async function readBody(req,max=500000){const chunks=[];let n=0;for await(const chunk of req){n+=chunk.length;fail(n<=max,'Upload is too large.',413);chunks.push(chunk);}return Buffer.concat(chunks);}
export async function createApp({dataDir=process.env.DATA_DIR||path.join(root,'data'),store=null,agent=null,voice=null,openaiKey=process.env.OPENAI_API_KEY,openrouterKey=process.env.OPENROUTER_API_KEY,videoVerifier=verifyVideo,clock=Date.now}={}){
 store=store||await new Store(dataDir,{clock}).init();agent=agent||new Agent(store,{key:openaiKey});voice=voice||new Voice(store,agent,{key:openaiKey});const attemptsRunning=new Map();
 const server=http.createServer(async(req,res)=>{
  const url=new URL(req.url,'http://localhost'),json=(status,data)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(JSON.stringify(data));};
  try{
   const origin=req.headers.origin;if(origin){const expected=process.env.PUBLIC_BASE_URL||`${req.headers['x-forwarded-proto']==='https'?'https':'http'}://${req.headers.host}`;fail(origin===expected,'This request must come from the Friction app.',403);}
   if(url.pathname==='/health'){json(200,{ok:true,service:'friction-v1',schema:1,models:{agent:'gpt-6-astra',voice:'gpt-live-1'},configured:{conversation:Boolean(openaiKey),video:Boolean(openrouterKey)},persistence:'file',time:Date.now()});return;}
   const files={'/':'index.html','/app':'index.html','/app.js':'app.js','/style.css':'style.css','/voice-client.js':'voice-client.js','/voice-cue.js':'voice-cue.js','/live-updates.js':'live-updates.js'};
   if(req.method==='GET'&&files[url.pathname]){const name=files[url.pathname];res.writeHead(200,{'Content-Type':name.endsWith('.html')?'text/html; charset=utf-8':name.endsWith('.css')?'text/css':'text/javascript','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' https://api.openai.com; media-src 'self' blob:; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"});res.end(await fs.readFile(path.join(root,'public',name)));return;}
   fail(url.pathname.startsWith('/api/v1/'),'Endpoint not found.',404);
   if(req.method==='POST'&&url.pathname==='/api/v1/installations'){
    const body=JSON.parse((await readBody(req,4000)).toString()||'{}');json(201,await store.register(body.installationId));return;
   }
   const install=store.authenticate(req.headers.authorization?.replace(/^Bearer /,'')),route=url.pathname.slice('/api/v1'.length);
   if(req.method==='GET'){
    if(route==='/state'){json(200,store.snapshot(install));return;}
    if(route==='/config'){json(200,store.envelope(store.get(install)));return;}
    if(route==='/events'){res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache, no-transform','X-Accel-Buffering':'no',Connection:'keep-alive'});const listener=e=>{if(e.installationId===install)res.write(`data: ${JSON.stringify({kind:e.kind,detail:e.detail})}\n\n`);};store.on('change',listener);const timer=setInterval(()=>res.write(': ping\n\n'),20000);res.on('close',()=>{clearInterval(timer);store.off('change',listener);});timer.unref?.();res.write('data: {"kind":"connected"}\n\n');return;}
    const attemptMatch=route.match(/^\/attempts\/([a-zA-Z0-9_-]+)$/);if(attemptMatch){const attempt=store.get(install).attempts[attemptMatch[1]];fail(attempt,'Recording not found.',404);json(200,attempt);return;}
   }
   fail(req.method==='POST','Endpoint not found.',404);
   const videoRoute=route.match(/^\/attempts\/([a-zA-Z0-9_-]+)\/video$/),raw=await readBody(req,videoRoute?76*1024*1024:12*1024*1024),body=videoRoute?null:JSON.parse(raw.toString()||'{}');
   if(route==='/ack'){json(200,await store.ack(install,body.revision,body.configHash));return;}
   if(route==='/statistics'){json(200,await store.statistics(install,body));return;}
   if(route==='/context'){json(200,await store.context(install,body.selectedCardId));return;}
   if(route==='/proposals'){json(201,await store.stage(install,body));return;}
   if(route==='/cancel'){json(200,await store.cancel(install,body));return;}
   if(route==='/chat'){json(200,await agent.chat(install,body.message));return;}
   if(route==='/voice/session'){json(201,await voice.create(install,body.sdp));return;}
   if(route==='/voice/close'){json(200,await voice.close(install,body.sessionId));return;}
   if(route==='/attempts'){json(201,await store.startAttempt(install,body));return;}
   const proposal=route.match(/^\/proposals\/([a-zA-Z0-9_-]+)\/(confirm|remove)$/);
   if(proposal){json(200,proposal[2]==='confirm'?await store.confirm(install,proposal[1],body):await store.remove(install,proposal[1],body.version,body.cardId));return;}
   const evidence=route.match(/^\/attempts\/([a-zA-Z0-9_-]+)\/(gps|video|correction|fallback)$/);
   if(evidence){const attemptId=evidence[1],method=evidence[2];
    if(method==='correction'){json(200,await store.correct(install,attemptId,body.value));return;}
    if(method==='fallback'){json(200,await store.fallback(install,attemptId,body.completed));return;}
    let attempt=store.get(install).attempts[attemptId];if(!attempt&&method==='gps')attempt=await store.startAttempt(install,{...body,attemptId});fail(attempt,'Start the recording before submitting video.',404);fail(attempt.task.verifier===method,'This recording uses another verifier.');if(attempt.receipt){json(200,attempt.receipt);return;}
    const operationKey=install+':'+attemptId;if(attemptsRunning.has(operationKey)){json(200,await attemptsRunning.get(operationKey));return;}
    const run=(async()=>{
     if(method==='gps'){const verdict=verifyGps(attempt,body);return store.finishAttempt(install,attemptId,{...verdict,endedAt:body.endedAt,timeZone:body.timeZone});}
     const fields=parseMultipart(raw,req.headers['content-type']);fail(Number.isFinite(+fields.endedAt)&&+fields.endedAt>=attempt.startedAt&&+fields.endedAt<=clock()+60000,'Invalid recording end time.');fail(openrouterKey,'Video verification is not configured.',503);
     const reservation=await store.reserve(install,'video',.5);let reported=false,providerStarted=false;
     try{const verdict=await videoVerifier(attempt,fields.video,{key:openrouterKey,onProviderStart:()=>{providerStarted=true;},onUsage:async usage=>{await store.settle(reservation,usage.cost,{model:process.env.VIDEO_MODEL||'google/gemini-3.8-flash',...usage});reported=true;}});return await store.finishAttempt(install,attemptId,{...verdict,endedAt:+fields.endedAt,timeZone:fields.timeZone||'UTC'});}finally{if(!reported){if(providerStarted)await store.settle(reservation,null,{reason:'Video provider usage unknown; reservation retained conservatively.'});else await store.release(reservation);}}
    })();attemptsRunning.set(operationKey,run);try{json(200,await run);}finally{attemptsRunning.delete(operationKey);}return;
   }
   throw new AppError('Endpoint not found.',404);
  }catch(e){if(!res.headersSent)json(e.status||((e instanceof SyntaxError)?400:500),{error:e.status||e instanceof SyntaxError?e.message:'The app could not finish that operation. Try again.'});else res.end();if(!e.status&&!(e instanceof SyntaxError))console.error('Request failed:',e.message);}
 });
 return{server,store,agent,voice};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const app=await createApp();const port=Number(process.env.PORT||4318);app.server.listen(port,process.env.HOST||'0.0.0.0',()=>console.log(`Friction v1 listening on ${port}`));for(const signal of ['SIGTERM','SIGINT'])process.on(signal,()=>{app.server.close();for(const session of app.voice.sessions.values())app.voice.close(session.installationId,session.sessionId).catch(()=>{});setTimeout(()=>process.exit(),1000).unref();});}
