// Only complete, unambiguous commands use the immediate cancellation path.
// Other phrasing goes to the model, which can select a specific proposal/receipt.
export function isCancellationRequest(text){
 const s=String(text||'').toLowerCase().replace(/[.,!?]/g,' ').replace(/\s+/g,' ').trim();
 return /^(?:(?:please|actually|wait|no|okay|ok) )*(?:cancel(?: (?:that|it|this|the change|the request))?|undo(?: (?:that|it|this|the change|the last change))?|revert(?: (?:that|it|this|the change|the last change))?|never mind|nevermind|stop(?: (?:that|the change|the request))?)(?: please)?$/.test(s);
}
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
export function restoredConfig(current,receipt){
 const next=structuredClone(current),before=receipt.before,after=receipt.expected;
 const restore=(target,oldValue,newValue,key)=>{if(same(oldValue,newValue))return;if(!same(target[key],newValue))throw Error(`Cannot undo: ${key} was changed again. Newer changes were preserved.`);target[key]=structuredClone(oldValue);};
 for(const key of Object.keys(before.settings))restore(next.settings,before.settings[key],after.settings[key],key);
 for(const task of after.tasks){const old=before.tasks.find(t=>t.id===task.id),live=next.tasks.find(t=>t.id===task.id);
  if(!old){if(!same(live,task))throw Error('Cannot undo: the created task was changed again.');next.tasks=next.tasks.filter(t=>t.id!==task.id);}
  else if(!same(old,task)){if(!live)throw Error('Cannot undo: task no longer exists.');for(const key of Object.keys(old))restore(live,old[key],task[key],key);}
 }
 return next;
}
