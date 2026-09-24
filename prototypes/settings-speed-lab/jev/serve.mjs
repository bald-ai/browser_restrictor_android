import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import {parseEnv} from 'node:util';
import {AsyncLocalStorage} from 'node:async_hooks';
import {createApp} from '../frozen/server.mjs';
import {PatchStore} from '../patch.mjs';
import {Agent as GeneralAgent} from '../candidate-agent.mjs';
import {JevAgent} from './agent.mjs';
const root=import.meta.dirname;
const env=parseEnv(await fs.readFile(path.resolve(root,'../../agent-ux-lab/.env'),'utf8'));
const key=process.env.OPENAI_API_KEY||env.OPENAI_API_KEY;
const results=path.resolve(root,'../results/jev',process.env.JEV_RUN_ID||'interactive');
await fs.mkdir(results,{recursive:true});
const traceContext=new AsyncLocalStorage(),metrics=await fs.readFile(path.join(results,'metrics.json'),'utf8').then(JSON.parse).catch(()=>[]);
let estimatedSpend=metrics.reduce((n,m)=>n+(m.estimatedUsd||0),0);
const budget=Number(process.env.JEV_LAB_BUDGET_USD||5);
for(const [variant,port,AgentClass] of [['general',4341,GeneralAgent],['jev',4342,JevAgent]]){
 const store=await new PatchStore(path.join(results,'data',variant)).init();
 const agent=new AgentClass(store,{key,onPath:p=>{traceContext.getStore().path=p;},onEvaluation:async c=>{const trace=traceContext.getStore();const cost=(c.usage?.inputTokens||0)*.04/1e6;estimatedSpend+=cost;trace.estimatedUsd+=cost;trace.calls.push({provider:'jev',...c});},fetcher:async(url,opts)=>{
  if(url!=='https://api.openai.com/v1/responses')throw Error('Unexpected provider endpoint');
  if(estimatedSpend+.35>budget)throw Error('Experiment budget reached');
  const start=performance.now(),res=await fetch(url,opts),body=await res.json(),usage=body.usage;
  const cost=usage?((usage.input_tokens-(usage.input_tokens_details?.cached_tokens||0))*20+(usage.input_tokens_details?.cached_tokens||0)*2+(usage.output_tokens||0)*100+(usage.input_tokens_details?.cache_write_tokens??usage.input_tokens)*5)/1e6:0;
  estimatedSpend+=cost;const trace=traceContext.getStore();trace.estimatedUsd+=cost;trace.calls.push({provider:'general',elapsedMs:performance.now()-start,status:res.status,request:JSON.parse(opts.body),response:body});return{ok:res.ok,status:res.status,json:async()=>body};
 }});
 const chat=agent.chat.bind(agent);
 agent.chat=async(...args)=>{
  if(estimatedSpend+.35>budget)throw Error('Experiment budget reached');
  const trace={id:crypto.randomUUID(),variant,installationId:args[0],message:args[1],path:variant,estimatedUsd:0,calls:[],startedAt:new Date().toISOString()};
  return traceContext.run(trace,async()=>{const start=performance.now();try{return await chat(...args);}catch(e){trace.error=e.message;throw e;}finally{trace.elapsedMs=performance.now()-start;await fs.writeFile(path.join(results,trace.id+'.json'),JSON.stringify(trace,null,2));const compact={...trace,calls:trace.calls.map(({request,response,answers,...rest})=>({...rest,usage:rest.usage||response?.usage}))};metrics.push(compact);await fs.writeFile(path.join(results,'metrics.json'),JSON.stringify(metrics,null,2));}});
 };
 const app=await createApp({store,agent,openaiKey:key});
 const original=app.server.listeners('request')[0];app.server.removeAllListeners('request');
 app.server.on('request',(req,res)=>{
  if(req.url==='/lab/metrics'){try{const install=store.authenticate(req.headers.authorization?.replace(/^Bearer /,''));res.setHeader('Content-Type','application/json');res.end(JSON.stringify(metrics.filter(m=>m.installationId===install)));}catch{res.writeHead(401);res.end('{}');}return;}
  // Text experiment only: keep voice out of the measured paths and spending.
  if(req.url?.startsWith('/api/v1/voice/')){res.writeHead(400,{'Content-Type':'application/json'});res.end(JSON.stringify({error:'This comparison measures text settings changes. Use the original lab for voice.'}));return;}
  original(req,res);
 });
 app.server.listen(port,'127.0.0.1',()=>console.log(`${variant}: http://localhost:${port}`));
}
http.createServer(async(req,res)=>{try{if(req.url==='/results.json'||req.url==='/heldout.json'){res.setHeader('Content-Type','application/json');res.end(await fs.readFile(path.join(results,req.url==='/heldout.json'?'heldout.json':'benchmark.json')).catch(()=>'{"status":"Not run yet"}'));return;}res.setHeader('Content-Type','text/html');res.end(await fs.readFile(path.join(root,'dashboard.html')));}catch{res.writeHead(500);res.end('Could not load experiment dashboard');}}).listen(4340,'127.0.0.1',()=>console.log('Experiment: http://localhost:4340'));
