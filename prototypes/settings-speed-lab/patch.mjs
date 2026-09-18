import {fail,clone,applyCards,validateConfig,labels,taskSchema} from './frozen/domain.mjs';
import {Store} from './frozen/store.mjs';
export function captureBase(store,install){const s=store.get(install),p=s.proposals.findLast(p=>p.status==='pending');return{revision:s.revision,proposalId:p?.id??null,version:p?.version??null,config:p?applyCards(s.config,p.cards):clone(s.config)};}
export function expandChanges(base,changes){
 fail(Array.isArray(changes)&&changes.length>0&&changes.length<=100,'Provide 1–100 explicit edits.');
 const config=clone(base),targets=new Set();
 for(const op of changes){
  fail(op&&typeof op==='object'&&!Array.isArray(op),'An edit must be an object.');
  const fields={set_setting:['action','field','value'],set_task_field:['action','taskId','field','value'],add_task:['action','task'],remove_task:['action','taskId']}[op.action];
  fail(fields&&Object.keys(op).length===fields.length&&Object.keys(op).every(k=>fields.includes(k)),'Unknown edit or extra fields.');
  const target=op.action==='set_setting'?'setting:'+op.field:op.action==='set_task_field'?'task:'+op.taskId+':'+op.field:'task:'+ (op.taskId||op.task?.id);
  fail(!targets.has(target),'Conflicting duplicate edits.');targets.add(target);
  if(op.action==='set_setting'){fail(Object.hasOwn(labels,op.field),'Unsupported setting.');config.settings[op.field]=clone(op.value);}
  else if(op.action==='add_task'){fail(op.task&&!config.tasks.some(t=>t.id===op.task.id),'Task ID already exists.');config.tasks.push(clone(op.task));}
  else{const i=config.tasks.findIndex(t=>t.id===op.taskId);fail(i>=0,'Unknown task ID.');if(op.action==='remove_task')config.tasks.splice(i,1);else{fail(op.field!=='id'&&Object.hasOwn(taskSchema.properties,op.field),'Unsupported task field.');config.tasks[i][op.field]=clone(op.value);}}
 }
 return validateConfig(config);
}
export class PatchStore extends Store {
 // The check executes inside the original store transaction, before its callback.
 stage(install,args,ctx){if(!ctx?.patchBase)return super.stage(install,args,ctx);const expected=ctx.patchBase;const guarded=Object.create(this);guarded.transaction=fn=>this.transaction(db=>{const now=captureBase(this,install);fail(now.revision===expected.revision&&now.proposalId===expected.proposalId&&now.version===expected.version,'The setup or review changed while this edit was being prepared.',409);return fn(db);});return Store.prototype.stage.call(guarded,install,args,ctx);}
}
