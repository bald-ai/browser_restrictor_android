import fs from 'node:fs/promises';
import path from 'node:path';
import {hash} from '../../backend-v1/domain.mjs';
import {summarize} from './report.mjs';

export async function rememberRun(dir,report,benchmarkDir=import.meta.dirname){
 if(!/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(report.runId))throw Error('Invalid benchmark run ID');
 const historyDir=path.join(benchmarkDir,'history');await fs.mkdir(historyDir,{recursive:true});
 const record={runId:report.runId,status:report.status,startedAt:report.startedAt,finishedAt:report.finishedAt,suiteVersion:report.suiteVersion,suiteHash:report.suiteHash,configHash:report.configHash,sourceHash:report.provenance.sourceHash,resultsHash:hash(report),config:report.config,totalUsd:report.totalUsd,scoredTrials:report.rows.filter(r=>!r.warmup).length,summary:summarize(report),evidencePath:path.relative(benchmarkDir,dir).split(path.sep).join('/')};
 const file=path.join(historyDir,report.runId+'.json'),serialized=JSON.stringify(record,null,2)+'\n';
 try{await fs.writeFile(file,serialized,{flag:'wx'});}catch(e){if(e.code!=='EEXIST')throw e;if(await fs.readFile(file,'utf8')!==serialized)throw Error('A different benchmark is already recorded under '+report.runId);}
 const records=await Promise.all((await fs.readdir(historyDir)).filter(n=>n.endsWith('.json')).map(async name=>JSON.parse(await fs.readFile(path.join(historyDir,name),'utf8'))));records.sort((a,b)=>a.startedAt.localeCompare(b.startedAt));
 const rows=records.map(r=>`| [${r.runId}](history/${r.runId}.json) | ${r.status} | ${r.suiteVersion} | ${r.scoredTrials} | ${r.config.repeats} | $${r.totalUsd.toFixed(6)} | [Report](${r.evidencePath}/index.html) |`);
 await fs.writeFile(path.join(benchmarkDir,'RESULTS.md'),`# Friction benchmark history\n\nStart here when revisiting benchmark results. Each linked history record retains the exact setup, suite/config/source hashes, correctness, coverage, timing, cost and failure counts. Full provider traces and source snapshots remain in the linked local run directory.\n\nHistory records are immutable and eligible for version control. Raw runs are local and Git-ignored. These files survive chat sessions; they are not an off-device backup until committed and pushed or otherwise backed up.\n\nSmoke runs validate the harness; do not use a single repetition to rank models. Only compare completed runs with compatible suites, source versions and workloads.\n\n| Run | Status | Suite | Scored trials | Repeats | Estimated total | Full evidence |\n| --- | --- | --- | --- | --- | --- | --- |\n${rows.join('\n')}\n`);
 return record;
}
