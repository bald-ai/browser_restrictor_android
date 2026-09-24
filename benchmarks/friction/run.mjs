import fs from 'node:fs/promises';import path from 'node:path';import {parseArgs} from 'node:util';import {execFileSync} from 'node:child_process';import {performance} from 'node:perf_hooks';
import {hash,validateConfig} from '../../backend-v1/domain.mjs';
import {Meter,validateStrategy,fixture,runTurn,observation,scoreTurn,auditReview,serialResult} from './engine.mjs';
import {writeReport} from './report.mjs';
import {rememberRun} from './history.mjs';
const root=path.resolve(import.meta.dirname,'../..');
export function schedule(cases,strategies,repeats,seed){let n=seed>>>0;const random=()=>{n=(Math.imul(n,1664525)+1013904223)>>>0;return n/4294967296;};const out=[];
 for(let rep=0;rep<repeats;rep++){const order=[...cases];for(let i=order.length-1;i>0;i--){const j=Math.floor(random()*(i+1));[order[i],order[j]]=[order[j],order[i]];}
  for(const [index,c]of order.entries()){const offset=(rep+index)%strategies.length;for(let k=0;k<strategies.length;k++)out.push({c,strategy:strategies[(k+offset)%strategies.length],rep,warmup:false});}}
 return out;
}
export function validateConfigFile(config,suite){
 for(const [key,min,max]of [['repeats',1,100],['warmups',0,10],['maxCalls',1,100000],['timeoutMs',100,300000]])if(!Number.isInteger(config[key])||config[key]<min||config[key]>max)throw Error('Invalid '+key);
 if(!Number.isInteger(config.seed)||!Number.isFinite(config.maxUsd)||config.maxUsd<=0)throw Error('A seed and positive maxUsd are required');
 if(!Array.isArray(config.strategies)||!config.strategies.length)throw Error('Choose at least one strategy');const ids=new Set();for(const s of config.strategies){if(!/^[a-z0-9][a-z0-9_-]*$/.test(s.id)||ids.has(s.id))throw Error('Strategy IDs must be unique safe names');ids.add(s.id);validateStrategy(s);}
 if(!suite.version||!Array.isArray(suite.cases)||!suite.cases.length)throw Error('Invalid suite');const caseIds=new Set();
 for(const c of suite.cases){if(!/^[a-z0-9][a-z0-9_-]*$/.test(c.id)||caseIds.has(c.id))throw Error('Invalid/duplicate case ID');caseIds.add(c.id);validateConfig(c.initial);if(c.pending)validateConfig(c.pending);if(!Array.isArray(c.turns)||!c.turns.length)throw Error('Case must have turns');for(const t of c.turns){if(typeof t.message!=='string'||!t.message||!['edit','guard','direct'].includes(t.expect?.kind))throw Error('Invalid turn');validateConfig(t.expect.saved);if(t.expect.review)validateConfig(t.expect.review);}}
 if(config.cases&&(!Array.isArray(config.cases)||!config.cases.length||new Set(config.cases).size!==config.cases.length||config.cases.some(id=>!caseIds.has(id))))throw Error('Unknown or duplicate case selection');
}
function hasBudget(result){return result.status==='budget'||(result.branches||[]).some(hasBudget);}
function nodes(s){return[s,...(s.steps||[]).flatMap(nodes)];}
async function archive(dir){
 const files=['package.json'];for(const name of await fs.readdir(path.join(root,'backend-v1')))if(name.endsWith('.mjs')||['package.json','package-lock.json'].includes(name))files.push('backend-v1/'+name);
 for(const name of await fs.readdir(path.join(root,'backend-v1/docs')))if(name.endsWith('.md'))files.push('backend-v1/docs/'+name);
 async function collect(relative){for(const e of await fs.readdir(path.join(root,relative),{withFileTypes:true})){const p=relative+'/'+e.name;if(e.isDirectory()){if(!['runs','history'].includes(e.name))await collect(p);}else if(e.name!=='RESULTS.md'&&/\.(mjs|json|md)$/.test(e.name))files.push(p);}}
 await collect('benchmarks/friction');const hashes={};for(const file of files){const data=await fs.readFile(path.join(root,file));hashes[file]=hash(data.toString());const dest=path.join(dir,'source',file);await fs.mkdir(path.dirname(dest),{recursive:true});await fs.writeFile(dest,data);}
 let revision=null,dirty=null;try{revision=execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();dirty=execFileSync('git',['status','--porcelain'],{cwd:root,encoding:'utf8'}).trim();}catch{}
 return{revision,dirty,sourceHash:hash(hashes),files:hashes,node:process.version,platform:process.platform,arch:process.arch};
}
async function main(){
 const {values}=parseArgs({options:{config:{type:'string',default:'benchmarks/friction/configs/comparison.json'},suite:{type:'string'},run:{type:'boolean',default:false},'run-id':{type:'string'},out:{type:'string'},cases:{type:'string'},repeats:{type:'string'},seed:{type:'string'},'max-usd':{type:'string'}}});
 const configPath=path.resolve(values.config),config=JSON.parse(await fs.readFile(configPath,'utf8'));if(values.cases)config.cases=values.cases.split(',');if(values.repeats)config.repeats=Number(values.repeats);if(values.seed)config.seed=Number(values.seed);if(values['max-usd'])config.maxUsd=Number(values['max-usd']);
 const suitePath=values.suite?path.resolve(values.suite):path.resolve(path.dirname(configPath),config.suite),suite=JSON.parse(await fs.readFile(suitePath,'utf8'));validateConfigFile(config,suite);const cases=suite.cases.filter(c=>!config.cases||config.cases.includes(c.id));
 const planned=schedule(cases,config.strategies,config.repeats,config.seed);console.log(JSON.stringify({suite:suite.version,cases:cases.length,scoredTrials:planned.length,repeats:config.repeats,strategies:config.strategies.map(s=>s.id),maxUsd:config.maxUsd,maxCalls:config.maxCalls,mode:values.run?'live':'plan-only'},null,2));if(!values.run){console.log('Add --run to execute paid provider calls. No calls made.');return;}
 const kinds=config.strategies.flatMap(nodes).map(s=>s.kind);if(kinds.includes('llm')&&!process.env.OPENAI_API_KEY)throw Error('Set OPENAI_API_KEY');if(kinds.includes('evaluator')&&!process.env.AI_GATEWAY_API_KEY)throw Error('Set AI_GATEWAY_API_KEY');
 const runId=values['run-id']||new Date().toISOString().replaceAll(':','-');if(!/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(runId))throw Error('Invalid run ID');
 const dir=path.resolve(values.out||path.join(import.meta.dirname,'runs'),runId);await fs.mkdir(path.dirname(dir),{recursive:true});await fs.mkdir(dir); // Refuse existing run IDs; never overwrite evidence.
 const provenance=await archive(dir);await fs.writeFile(path.join(dir,'suite.json'),JSON.stringify(suite,null,2));await fs.writeFile(path.join(dir,'config.json'),JSON.stringify(config,null,2));
 const meter=new Meter(config),report={runId,status:'running',startedAt:new Date().toISOString(),suiteVersion:suite.version,plannedScoredTrials:planned.length,plannedWarmups:config.warmups*config.strategies.length,suiteHash:hash(suite),configHash:hash(config),config,provenance,rows:[],calls:meter.calls,totalUsd:0};let stop=false;process.on('SIGINT',()=>{stop=true;console.log('Stopping after the active trial; its calls and results will be retained.');});
 let traceQueue=Promise.resolve();meter.onEvent=event=>{traceQueue=traceQueue.then(()=>fs.appendFile(path.join(dir,'calls.jsonl'),JSON.stringify(event)+'\n'));};
 const save=async()=>{report.totalUsd=meter.spent;const temp=path.join(dir,'results.json.tmp');await fs.writeFile(temp,JSON.stringify(report,null,2));await fs.rename(temp,path.join(dir,'results.json'));};await save();
 const warmups=[];for(let rep=0;rep<config.warmups;rep++)for(const strategy of config.strategies)warmups.push({c:cases[0],strategy,rep,warmup:true});const plan=[...warmups,...planned];
 try{
  for(const [index,job]of plan.entries()){
   if(stop){report.status='interrupted';break;}const{c,strategy,rep,warmup}=job;const trial=String(index).padStart(5,'0')+'-'+strategy.id+'-'+c.id;let state=await fixture(path.join(dir,'stores',trial,'initial'),c);const turns=[];const start=performance.now();let elapsedMs=0;
   for(const [turnIndex,turn]of c.turns.entries()){
    const result=await runTurn(strategy,state,turn.message,{dir:path.join(dir,'stores',trial,'turn-'+turnIndex),trial,meter,timeoutMs:config.timeoutMs,openaiKey:process.env.OPENAI_API_KEY,gatewayKey:process.env.AI_GATEWAY_API_KEY});state=result.state;const actual=observation(state),score=scoreTurn(turn.expect,result,actual);if(hasBudget(result)){score.outcome='budget';score.completed=false;score.actionCorrect=false;}elapsedMs+=result.elapsedMs;
    turns.push({message:turn.message,expected:turn.expect,actual,...score,result:serialResult(result)});
    // Do not score later turns from a state that already diverged from the script.
    if(!['passed','needs-review'].includes(score.outcome))break;
   }
   const decisionCorrect=turns.length===c.turns.length&&turns.every(t=>t.actionCorrect===true),audit=await auditReview(state);
   const firstFailure=turns.find(t=>!['passed','needs-review'].includes(t.outcome));const kind=c.turns.every(t=>t.expect.kind==='guard')?'guard':'action';
   const outcome=firstFailure?.outcome||(audit.status==='failed'?'lifecycle-failed':kind==='guard'?'needs-review':'passed');
   report.rows.push({id:trial,caseId:c.id,category:c.category,split:c.split,kind,strategy:strategy.id,rep,warmup,outcome,decisionCorrect,completed:turns.length===c.turns.length&&turns.every(t=>t.completed),safe:turns.every(t=>t.safe),elapsedMs,wallMs:performance.now()-start,audit,turns});
   await save();console.log(`${warmup?'warmup ':''}${strategy.id} / ${c.id}: ${outcome} (${Math.round(elapsedMs)} ms)`);
   if(turns.some(t=>t.outcome==='budget')){report.status='budget-stopped';break;}
  }
  if(report.status==='running')report.status='complete';
 }catch(e){report.status='error';report.error=e.stack;process.exitCode=1;}
 finally{await traceQueue;report.finishedAt=new Date().toISOString();await save();await writeReport(dir,report);await rememberRun(dir,report);console.log('Results: '+dir);}
}
if(process.argv[1]&&path.resolve(process.argv[1])===path.join(import.meta.dirname,'run.mjs'))main().catch(e=>{console.error(e.message);process.exitCode=1;});
