import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import {parseEnv} from 'node:util';
import {AsyncLocalStorage} from 'node:async_hooks';
import {createApp} from './frozen/server.mjs';
import {Store} from './frozen/store.mjs';
import {Agent as Baseline} from './frozen/agent.mjs';
import {Agent as Candidate} from './candidate-agent.mjs';
import {PatchStore} from './patch.mjs';
const root=import.meta.dirname;
const env=parseEnv(await fs.readFile(path.resolve(root,'../agent-ux-lab/.env'),'utf8'));
const key=process.env.OPENAI_API_KEY||env.OPENAI_API_KEY;
if(!key)throw Error('Missing experiment API key');
const traceContext=new AsyncLocalStorage(),stores=[],metrics=[];
const limit=Number(process.env.LAB_BUDGET_USD||8);
let reserved=0;
await fs.mkdir(path.join(root,'results/calls'),{recursive:true});
for(const [variant,port,StoreClass,AgentClass]of [['baseline',4331,Store,Baseline],['patch',4332,PatchStore,Candidate]]){
 const store=await new StoreClass(path.join(root,'data',variant)).init();stores.push(store);
 const agent=new AgentClass(store,{key,fetcher:async(url,opts)=>{
  if(!String(url).startsWith('https://api.openai.com/v1/responses'))throw Error('Unexpected model endpoint');
  if(stores.reduce((n,s)=>n+s.usage().usd,0)+reserved+.35>limit)throw Error('Local experiment spending guard reached');
  reserved+=.35;const start=performance.now(),body=JSON.parse(opts.body),trace=traceContext.getStore();
  try{
   const res=await fetch(url,opts),json=await res.json();
   const call={elapsedMs:performance.now()-start,status:res.status,request:body,response:json};trace?.calls.push(call);
   const file=`${Date.now()}-${variant}-${trace?.id||'manual'}-${trace?.calls.length||0}.json`;
   await fs.writeFile(path.join(root,'results/calls',file),JSON.stringify(call,null,2),{mode:0o600});
   return{ok:res.ok,status:res.status,json:async()=>json};
  }finally{reserved-=.35;}
 }});
 const chat=agent.chat.bind(agent);
 agent.chat=async(install,message,options)=>{const trace={id:crypto.randomUUID(),variant,installationId:install,message,startedAt:Date.now(),calls:[]},before=store.usage(install).usd;return traceContext.run(trace,async()=>{try{return await chat(install,message,options);}catch(e){trace.error=e.message;throw e;}finally{trace.elapsedMs=Date.now()-trace.startedAt;trace.estimatedUsd=store.usage(install).usd-before;trace.calls=trace.calls.map(c=>({elapsedMs:c.elapsedMs,httpStatus:c.status,model:c.response.model,serviceTier:c.response.service_tier,status:c.response.status,usage:c.response.usage,argumentChars:(c.response.output||[]).filter(o=>o.type==='function_call').reduce((n,o)=>n+(o.arguments?.length||0),0),functions:(c.response.output||[]).filter(o=>o.type==='function_call').map(o=>o.name),providerError:c.response.error||null}));metrics.push(trace);await fs.writeFile(path.join(root,'results/metrics.json'),JSON.stringify(metrics,null,2));}});};
 const app=await createApp({store,agent,openaiKey:key});
 const original=app.server.listeners('request')[0];app.server.removeAllListeners('request');
 app.server.on('request',async(req,res)=>{if(req.url==='/lab/metrics'){try{const install=store.authenticate(req.headers.authorization?.replace(/^Bearer /,''));res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(metrics.filter(m=>m.installationId===install)));}catch{res.writeHead(401);res.end('{}');}return;}original(req,res);});
 app.server.listen(port,'127.0.0.1',()=>console.log(`${variant}: http://localhost:${port}`));
}
http.createServer(async(req,res)=>{try{if(req.url==='/results.json'){res.setHeader('Content-Type','application/json');res.end(await fs.readFile(path.join(root,'results/benchmark.json')).catch(()=>'{"status":"Ready to run"}'));return;}res.setHeader('Content-Type','text/html');res.end(await fs.readFile(path.join(root,'dashboard.html')));}catch{res.writeHead(500);res.end('Could not load lab');}}).listen(4330,'127.0.0.1',()=>console.log('Dashboard: http://localhost:4330'));
