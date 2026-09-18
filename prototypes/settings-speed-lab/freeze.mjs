import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
const root=import.meta.dirname,source=path.resolve(root,'../../backend-v1'),dest=path.join(root,'frozen');
try{await fs.access(dest);throw Error('Snapshot already exists. Preserve it for reproducibility; create a new experiment to update it.');}catch(e){if(e.code!=='ENOENT')throw e;}
await fs.mkdir(dest,{recursive:true});
const manifest={createdAt:new Date().toISOString(),source,files:{}};
for(const name of await fs.readdir(source)){if(name.endsWith('.mjs')||['public','test','package.json'].includes(name))await fs.cp(path.join(source,name),path.join(dest,name),{recursive:true});}
async function hashDir(dir){for(const entry of await fs.readdir(dir,{withFileTypes:true})){const file=path.join(dir,entry.name);if(entry.isDirectory())await hashDir(file);else manifest.files[path.relative(dest,file)]=crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex');}}
await hashDir(dest);
await fs.symlink(path.join(source,'node_modules'),path.join(dest,'node_modules'),'dir');
await fs.mkdir(path.join(root,'results'),{recursive:true});await fs.writeFile(path.join(root,'results/manifest.json'),JSON.stringify(manifest,null,2));
let candidate=await fs.readFile(path.join(dest,'agent.mjs'),'utf8');
candidate=candidate.replace("from './domain.mjs'","from './frozen/domain.mjs'").replace("from './prompts.mjs'","from './patch-prompts.mjs'");
const needle='runtime.turn=ctx;';if(!candidate.includes(needle))throw Error('Agent source changed; review generation.');
candidate="import {captureBase,expandChanges} from './patch.mjs';\n"+candidate.replace(needle,needle+'ctx.patchBase=captureBase(store,install);').replace('await store.stage(install,args,ctx)','await store.stage(install,{...args,config:expandChanges(ctx.patchBase.config,args.changes)},ctx)');
await fs.writeFile(path.join(root,'candidate-agent.mjs'),candidate);
console.log('Frozen baseline and generated candidate; '+Object.keys(manifest.files).length+' source hashes recorded.');
