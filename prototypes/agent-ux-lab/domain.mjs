export const seed = () => ({
  settings:{waitSeconds:12,minutesPerCredit:3,sessionMinutes:[3,5,10],budgetReset:'20:00',dayReset:'04:00',youtubeCredits:4,emergencyMinutes:15},
  tasks:[
    {id:'pushups',name:'Push-ups',verifier:'video',metric:'repetitions',unitsPerCredit:5,correctionMinimum:1,instructions:'Count completed push-ups with visible lowering and raising of the body.',paused:false},
    {id:'plank',name:'Plank',verifier:'video',metric:'seconds',unitsPerCredit:15,correctionMinimum:1,instructions:'Measure seconds actively holding a plank position.',paused:false},
    {id:'sprint',name:'Sprint',verifier:'gps',metric:'meters',unitsPerCredit:120,correctionMinimum:1,instructions:'Count qualifying sprint distance at at least 15 km/h.',paused:false},
    {id:'walk',name:'Take a walk',verifier:'gps',metric:'meters',unitsPerCredit:1000,correctionMinimum:1,instructions:'Count walking distance. Exclude speeds above 18 km/h, GPS drift, inaccurate readings and gaps.',paused:false}
  ]
});
export const statistics={label:'SIMULATED demo statistics',today:{creditsEarned:3,minutesEarned:9,minutesUsed:5,balanceMinutes:4},week:{tasksCompleted:18,minutesEarned:54,minutesUsed:41,mostUsedApp:'Chrome'}};
const integer=(v,min,max)=>Number.isInteger(v)&&v>=min&&v<=max;
const plain=x=>x&&typeof x==='object'&&!Array.isArray(x);
export function validateConfig(c){
  if(!plain(c)||!plain(c.settings)||!Array.isArray(c.tasks))throw Error('A full settings object and tasks array are required.');
  const s=c.settings;
  const allowed=(obj,keys)=>{if(Object.keys(obj).some(k=>!keys.includes(k)))throw Error('Unsupported configuration field.');};
  allowed(c,['settings','tasks']);allowed(s,['waitSeconds','minutesPerCredit','sessionMinutes','budgetReset','dayReset','youtubeCredits','emergencyMinutes']);
  for(const [k,min,max] of [['waitSeconds',0,120],['minutesPerCredit',1,120],['youtubeCredits',1,100],['emergencyMinutes',1,120]])if(!integer(s[k],min,max))throw Error(`${k} must be an integer from ${min} to ${max}.`);
  if(!Array.isArray(s.sessionMinutes)||s.sessionMinutes.length!==3||s.sessionMinutes.some(x=>!integer(x,1,120)))throw Error('Three session lengths from 1 to 120 minutes required.');
  for(const k of ['budgetReset','dayReset'])if(!/^([01]\d|2[0-3]):[0-5]\d$/.test(s[k]))throw Error('Daily resets are mandatory HH:MM times.');
  if(c.tasks.length>20)throw Error('This small demo allows at most 20 tasks.');
  const ids=new Set();
  for(const t of c.tasks){
    if(!plain(t)||!/^[a-z][a-z0-9_-]{0,40}$/.test(t.id)||ids.has(t.id))throw Error('Task IDs must be unique lowercase identifiers.');ids.add(t.id);
    allowed(t,['id','name','verifier','metric','unitsPerCredit','correctionMinimum','instructions','paused']);
    if(typeof t.name!=='string'||!t.name.trim()||t.name.length>80)throw Error('Task name must be 1–80 characters.');
    if(!['video','gps'].includes(t.verifier)||!['repetitions','seconds','meters'].includes(t.metric))throw Error('Unsupported verifier/measurement.');
    if(t.verifier==='video'&&t.metric==='meters'||t.verifier==='gps'&&t.metric==='repetitions')throw Error('Measurement incompatible with verifier.');
    if(!integer(t.unitsPerCredit,1,100000)||!integer(t.correctionMinimum,1,100000))throw Error('Positive integer reward and correction thresholds required.');
    if(typeof t.instructions!=='string'||!t.instructions.trim()||t.instructions.length>2000)throw Error('Judging instructions required (up to 2,000 characters).');
    if(typeof t.paused!=='boolean')throw Error('paused must be true or false.');
  }
  return structuredClone(c);
}
export function estimate(usage,model,serviceTier='default'){
  if(!usage)return null;
  const rates={'gpt-6-astra':[10,1,50],'gpt-5.6-terra':[2,.2,12],'gpt-5.6-luna':[.2,.02,1.2],'gpt-5.6-sol':[4,.4,20]};const [input,cached,output]=rates[model]||rates['gpt-5.6-terra'];
  const n=usage.input_tokens||0,c=usage.input_tokens_details?.cached_tokens||0,o=usage.output_tokens||0;
  const base=((n-c)*input+c*cached+o*output)/1e6;
  const writes=usage.input_tokens_details?.cache_write_tokens;const knownWrites=Number.isFinite(writes);const usd=base+(knownWrites?writes*input*.25/1e6:0);
  const multiplier=['gpt-6-astra','gpt-5.6-sol'].includes(model)&&['fast','priority'].includes(serviceTier)?2:1;
  return {usd:usd*multiplier,withCacheWriteAllowance:(knownWrites?usd:base+(n-c)*input*.25/1e6)*multiplier,cacheWriteTokens:knownWrites?writes:null,inputTokens:n,cachedTokens:c,outputTokens:o,reasoningTokens:usage.output_tokens_details?.reasoning_tokens||0};
}
export const taskSchema={type:'object',properties:{id:{type:'string'},name:{type:'string'},verifier:{type:'string',enum:['video','gps']},metric:{type:'string',enum:['repetitions','seconds','meters']},unitsPerCredit:{type:'integer'},correctionMinimum:{type:'integer'},instructions:{type:'string'},paused:{type:'boolean'}},required:['id','name','verifier','metric','unitsPerCredit','correctionMinimum','instructions','paused'],additionalProperties:false};
export const configSchema={type:'object',properties:{settings:{type:'object',properties:{waitSeconds:{type:'integer'},minutesPerCredit:{type:'integer'},sessionMinutes:{type:'array',items:{type:'integer'}},budgetReset:{type:'string'},dayReset:{type:'string'},youtubeCredits:{type:'integer'},emergencyMinutes:{type:'integer'}},required:['waitSeconds','minutesPerCredit','sessionMinutes','budgetReset','dayReset','youtubeCredits','emergencyMinutes'],additionalProperties:false},tasks:{type:'array',items:taskSchema}},required:['settings','tasks'],additionalProperties:false};
