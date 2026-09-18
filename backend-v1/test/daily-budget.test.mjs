import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {Store} from '../store.mjs';
import {Agent} from '../agent.mjs';
import {seed, validateConfig, hash} from '../domain.mjs';
import {expandChanges} from '../edits.mjs';
import {changeSchema} from '../edit-schema.mjs';

async function fixture(t) {
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'daily-budget-'));
 t.after(()=>fs.rm(dir,{recursive:true,force:true}));
 const store=await new Store(dir).init(), identity=await store.register();
 return {store,dir,install:identity.installationId,identity};
}
const setting=value=>({action:'set_setting',field:'dailyBudgetMinutes',value});

test('allowance has a bounded agent operation and retired reset is unsupported',()=>{
 assert.equal(seed().settings.dailyBudgetMinutes,0);
 const operations=changeSchema.anyOf.filter(op=>op.properties.action.enum[0]==='set_setting');
 assert.deepEqual(operations.find(op=>op.properties.field.enum[0]==='dailyBudgetMinutes').properties.value,{type:'integer',minimum:0,maximum:30});
 assert(!operations.some(op=>op.properties.field.enum[0]==='budgetReset'));
 for(const value of [0,1,15,30])assert.equal(expandChanges(seed(),[setting(value)]).settings.dailyBudgetMinutes,value);
 for(const value of [-1,31,1.5,'20',null,Infinity])assert.throws(()=>expandChanges(seed(),[setting(value)]));
 assert.throws(()=>expandChanges(seed(),[{action:'set_setting',field:'budgetReset',value:'20:00'}]),/Unsupported setting/);
 const config=seed();config.settings.budgetReset='20:00';assert.throws(()=>validateConfig(config),/Unsupported configuration field/);
});

test('agent proposes allowance and daily time together, user approves, phone acknowledges, undo persists',async t=>{
 const {store,dir,install}=await fixture(t);
 const agent=new Agent(store,{key:'mock',fetcher:async()=>({ok:true,json:async()=>({status:'completed',output:[{type:'function_call',name:'propose_changes',call_id:'daily',arguments:JSON.stringify({changes:[setting(30),{action:'set_setting',field:'dayReset',value:'06:30'}],expectedRevision:0,title:'Daily allowance and reset'})}]})})});
 await agent.chat(install,'Give me 30 minutes at the daily reset and set it to 06:30.');
 assert.equal(store.get(install).config.settings.dailyBudgetMinutes,0);
 assert.equal(store.get(install).proposals.at(-1).cards.length,2);
 const saved=await agent.chat(install,'Apply');
 assert.equal(saved.config.settings.dailyBudgetMinutes,30);
 assert.equal(saved.config.settings.dayReset,'06:30');
 assert.equal(saved.applicationStatus,'awaiting_device');
 assert.equal((await store.ack(install,saved.revision,hash(saved.config))).applicationStatus,'applied');
 const loaded=await new Store(dir).init();assert.equal(loaded.get(install).config.settings.dailyBudgetMinutes,30);
 const undone=await loaded.cancel(install);assert.equal(undone.config.settings.dailyBudgetMinutes,0);assert.equal(undone.config.settings.dayReset,'04:00');
});

test('migration retires old reset without losing tasks, receipts, identity or unrelated pending edits',async t=>{
 const {store,dir,install,identity}=await fixture(t),s=store.get(install);
 store.db.schema=1;delete s.config.settings.dailyBudgetMinutes;s.config.settings.budgetReset='22:30';
 s.config.tasks[0].name='My pushups';s.revisions[0].config=structuredClone(s.config);s.appliedRevision=0;
 s.proposals.push({id:'review',status:'pending',version:1,baseRevision:0,cards:[
  {id:'setting:budgetReset',kind:'setting',key:'budgetReset',before:'22:30',after:'21:30'},
  {id:'setting:waitSeconds',kind:'setting',key:'waitSeconds',before:12,after:5,title:'Waiting time'}]});
 s.selectedCardId='setting:budgetReset';s.attempts.old={receipt:{id:'historical',minutesRewardEligible:false}};
 await store.persist();
 const migrated=await new Store(dir).init(),m=migrated.get(install);
 assert.equal(migrated.authenticate(identity.token),install);
 assert.equal(m.config.settings.dailyBudgetMinutes,0);assert(!('budgetReset' in m.config.settings));
 assert.equal(m.config.tasks[0].name,'My pushups');assert.deepEqual(m.attempts,s.attempts);
 assert.equal(m.revision,1);assert.equal(migrated.snapshot(install).applicationStatus,'awaiting_device');
 assert.equal(m.proposals[0].version,2);assert.equal(m.proposals[0].baseRevision,1);assert.equal(m.proposals[0].cards.length,1);
 assert.equal(m.selectedCardId,null);
 await assert.rejects(migrated.confirm(install,'review',{version:1}),/review changed/i);
 const out=await migrated.confirm(install,'review',{version:2});assert.equal(out.config.settings.waitSeconds,5);
 const restarted=await new Store(dir).init();assert.equal(restarted.get(install).revision,2);
});
