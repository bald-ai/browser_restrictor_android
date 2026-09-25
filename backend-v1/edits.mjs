import {fail,clone,applyCards,validateConfig,labels,taskSchema} from './domain.mjs';
export function captureBase(store,install){const s=store.get(install),p=s.proposals.findLast(p=>p.status==='pending');return{revision:s.revision,proposalId:p?.id??null,version:p?.version??null,config:p?applyCards(s.config,p.cards):clone(s.config)};}
function editTarget(op){
 fail(op&&typeof op==='object'&&!Array.isArray(op),'An edit must be an object.');
 const fields={set_setting:['action','field','value'],set_task_field:['action','taskId','field','value'],add_task:['action','task'],remove_task:['action','taskId']}[op.action];
 fail(Array.isArray(fields)&&Object.keys(op).length===fields.length&&Object.keys(op).every(k=>fields.includes(k)),'Unknown edit or extra fields.');
 if(op.action==='set_setting')return 'setting:'+op.field;
 if(op.action==='set_task_field')return 'task:'+op.taskId+':'+op.field;
 return 'task:'+(op.taskId||op.task?.id);
}
function applyEdit(config,op){
 if(op.action==='set_setting'){
  fail(Object.hasOwn(labels,op.field),'Unsupported setting.');config.settings[op.field]=clone(op.value);return;
 }
 if(op.action==='add_task'){
  fail(op.task&&!config.tasks.some(t=>t.id===op.task.id),'Task ID already exists.');config.tasks.push(clone(op.task));return;
 }
 const i=config.tasks.findIndex(t=>t.id===op.taskId);fail(i>=0,'Unknown task ID.');
 if(op.action==='remove_task')config.tasks.splice(i,1);
 else{fail(op.field!=='id'&&Object.hasOwn(taskSchema.properties,op.field),'Unsupported task field.');config.tasks[i][op.field]=clone(op.value);}
}
export function expandChanges(base,changes){
 fail(Array.isArray(changes)&&changes.length>0&&changes.length<=100,'Provide 1–100 explicit edits.');
 const config=clone(base),targets=new Set();
 for(const op of changes){
  const target=editTarget(op);fail(!targets.has(target),'Conflicting duplicate edits.');targets.add(target);
  applyEdit(config,op);
 }
 return validateConfig(config);
}
