// Instrument every production JavaScript file before testing: untouched branches
// have explicit zero counters rather than V8's synthetic whole-file branch.
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {spawnSync} from 'node:child_process';
import {createRequire} from 'node:module';
const root=path.resolve(import.meta.dirname,'..');
if (!process.env.VERIFICATION_SESSION_ROOT) {
 const child=spawnSync('python3',[path.join(root,'scripts/verification-session.py'),'coverage',...process.argv.slice(2)],{stdio:'inherit'});
 process.exit(child.status??1);
}
const require=createRequire(path.join(root,'backend-v1/package.json'));
const {createInstrumenter}=require('istanbul-lib-instrument');
const {createCoverageMap}=require('istanbul-lib-coverage');
const temporary=await fs.mkdtemp(path.join(os.tmpdir(),'friction-coverage-'));
const runId='istanbul-'+Date.now();
const evidence=path.join(root,'quality/logs',runId);await fs.mkdir(evidence,{recursive:true});
const output=path.join(root,'quality/metrics/istanbul');
await fs.mkdir(output,{recursive:true});
const production=[];
for(const file of await fs.readdir(path.join(root,'backend-v1')))if(file.endsWith('.mjs'))production.push(file);
for(const file of await fs.readdir(path.join(root,'backend-v1/public')))if(file.endsWith('.js'))production.push('public/'+file);
try {
 const backend=path.join(temporary,'backend-v1');
 await fs.cp(path.join(root,'backend-v1'),backend,{recursive:true,filter:p=>!['node_modules','data','.env'].includes(path.basename(p))});
 await fs.symlink(path.join(root,'backend-v1/node_modules'),path.join(backend,'node_modules'));
 for(const file of ['AGENTS.md','AGENT_CAPABILITIES.md'])await fs.copyFile(path.join(root,file),path.join(temporary,file));
 const coverage=createCoverageMap({});
 for(const file of production){
  const original=path.join(root,'backend-v1',file),instrumenter=createInstrumenter({esModules:true,compact:false});
  const code=instrumenter.instrumentSync(await fs.readFile(original,'utf8'),original);
  coverage.addFileCoverage(instrumenter.lastFileCoverage());await fs.writeFile(path.join(backend,file),code);
 }
 const raw=path.join(temporary,'raw');await fs.mkdir(raw);
 const preload=path.join(temporary,'save.cjs');
 await fs.writeFile(preload,`process.on('exit',()=>{if(global.__coverage__)require('node:fs').writeFileSync(${JSON.stringify(raw)}+'/'+process.pid+'.json',JSON.stringify(global.__coverage__));});`);
 const tests=(await fs.readdir(path.join(backend,'test'))).filter(p=>p.endsWith('.test.mjs')).map(p=>'test/'+p);
 const result=spawnSync(process.execPath,['--require',preload,'--test',...tests],{cwd:backend,env:{...process.env,OPENAI_API_KEY:'',OPENROUTER_API_KEY:'',PUBLIC_BASE_URL:''},encoding:'utf8',timeout:120000,maxBuffer:30*1024*1024});
 await fs.writeFile(path.join(evidence,'tests.log'),(result.stdout||'')+(result.stderr||'')+(result.error?.stack||''));
 await fs.writeFile(path.join(evidence,'tests.exit'),String(result.status??'error')+'\n');
 for(const file of await fs.readdir(raw))coverage.merge(JSON.parse(await fs.readFile(path.join(raw,file),'utf8')));
 await fs.writeFile(path.join(output,'coverage-final.json'),JSON.stringify(coverage.toJSON(),null,2));
 const summary={};
 for(const component of ['server','browser']){
  const componentMap=createCoverageMap({});
  for(const file of coverage.files())if(file.includes('/public/')===(component==='browser'))componentMap.addFileCoverage(coverage.fileCoverageFor(file));
  summary[component]=componentMap.getCoverageSummary().toJSON();
 }
 await fs.writeFile(path.join(output,'summary.json'),JSON.stringify(summary,null,2));
 await fs.writeFile(path.join(evidence,'summary.json'),JSON.stringify(summary,null,2));
 console.log(JSON.stringify({runId,testExit:result.status,summary},null,2));
 process.exitCode=result.status??1;
}finally{await fs.rm(temporary,{recursive:true,force:true});}
