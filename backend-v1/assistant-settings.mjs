import {fail} from './domain.mjs';

// Sol 6 support and pricing verified 2026-09-23 against official docs and live benchmarks.
// Verified against the official model pages and /api/docs/pricing on 2026-09-20;
// GPT-6.1 Sol on 2026-10-08 (no `none` effort; cached input is $0.10).
// Fast pricing is explicitly published for all three models; do not infer support
// for future additions from the generic Responses API service_tier enum.
export const assistantModels = [
 {id:'gpt-6-sol',label:'Sol 6',efforts:['none','low','medium','high','xhigh','max'],serviceTiers:['default','fast'],input:2,cached:.2,output:10},
 {id:'gpt-6.1-sol',label:'Sol 6.1',efforts:['low','medium','high','xhigh','max'],serviceTiers:['default','fast'],input:2,cached:.1,output:10},
 {id:'gpt-6-astra',label:'Astra',efforts:['low','medium','high','xhigh','max'],serviceTiers:['default','fast'],input:10,cached:1,output:50},
 {id:'gpt-5.6-luna',label:'Luna',efforts:['none','low','medium','high','xhigh','max'],serviceTiers:['default','fast'],input:.2,cached:.02,output:1.2},
 {id:'gpt-5.6-terra',label:'Terra',efforts:['none','low','medium','high','xhigh','max'],serviceTiers:['default','fast'],input:2,cached:.2,output:12},
];
export const defaultAssistantSettings={model:'gpt-6.1-sol',reasoning:'low',serviceTier:'fast'};
export const assistantOptions=assistantModels.map(({id,label,efforts,serviceTiers})=>({id,label,efforts,serviceTiers}));
export function validateAssistantSettings(value){
 fail(value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).length===3&&Object.keys(value).every(k=>Object.hasOwn(defaultAssistantSettings,k)),'Choose a model, reasoning effort and processing speed.');
 const model=assistantModels.find(m=>m.id===value.model);
 fail(model,'Unsupported assistant model.');
 fail(model.efforts.includes(value.reasoning),'This reasoning effort is not supported by the selected model.');
 fail(model.serviceTiers.includes(value.serviceTier),'This processing speed is not supported by the selected model.');
 return {...value};
}
export function assistantSettingsFor(installation){return validateAssistantSettings(installation.assistantSettings||defaultAssistantSettings);}
export function responseOptions(settings){
 const s=validateAssistantSettings(settings);
 return {model:s.model,service_tier:s.serviceTier,reasoning:{effort:s.reasoning},max_output_tokens:{none:2400,low:2400,medium:8192,high:16384,xhigh:32768,max:32768}[s.reasoning]};
}
export function estimatedModelCost(settings,usage,actualTier=settings.serviceTier){
 const model=assistantModels.find(m=>m.id===settings.model);
 const multiplier=['fast','priority'].includes(actualTier)?2:1;
 const input=usage.input_tokens||0,cached=usage.input_tokens_details?.cached_tokens||0;
 const writes=usage.input_tokens_details?.cache_write_tokens;
 // Cache-write tokens are part of uncached input, with an additional 25% charge.
 return ((input-cached)*model.input+cached*model.cached+(usage.output_tokens||0)*model.output+(Number.isFinite(writes)?writes:input-cached)*model.input*.25)*multiplier/1e6;
}
export function reserveModelCost(settings,body){
 return estimatedModelCost(settings,{input_tokens:Buffer.byteLength(JSON.stringify(body)),output_tokens:body.max_output_tokens});
}
