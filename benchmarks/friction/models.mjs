// Benchmark-only registration: importing the app does not expose these candidates
// in its settings UI. The benchmark still uses the real Agent and Store.
import {assistantModels} from '../../backend-v1/assistant-settings.mjs';

// Verified 2026-09-23 against the official model pages and pricing page:
// https://developers.openai.com/api/docs/models/gpt-6-luna
// https://developers.openai.com/api/docs/models/gpt-6-sol
// https://developers.openai.com/api/docs/pricing
// Both support none/low and Fast at 2x Standard rates. Only the requested
// two lowest efforts are enabled here. Rates are USD per million tokens.
export const benchmarkModels = [
 {id:'gpt-6-luna',label:'Luna 6',efforts:['none','low'],serviceTiers:['default','fast'],input:.1,cached:.01,output:.5},
 {id:'gpt-6-sol',label:'Sol 6',efforts:['none','low'],serviceTiers:['default','fast'],input:2,cached:.2,output:10},
];
for (const model of benchmarkModels) {
 if (!assistantModels.some(existing=>existing.id===model.id)) assistantModels.push(model);
}
