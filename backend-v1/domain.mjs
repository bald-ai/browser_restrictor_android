import crypto from 'node:crypto';
export class AppError extends Error { constructor(message,status=400){super(message);this.status=status;} }
export const fail=(condition,message,status=400)=>{if(!condition)throw new AppError(message,status);};
export const clone=x=>structuredClone(x);
export const id=()=>crypto.randomUUID();
export const stable=x=>Array.isArray(x)?`[${x.map(stable).join(',')}]`:x&&typeof x==='object'?`{${Object.keys(x).sort().map(k=>JSON.stringify(k)+':'+stable(x[k])).join(',')}}`:JSON.stringify(x);
export const equal=(a,b)=>stable(a)===stable(b);
export const hash=x=>crypto.createHash('sha256').update(typeof x==='string'?x:stable(x)).digest('hex');
export const labels={waitSeconds:'Waiting time',minutesPerCredit:'Minutes per credit',sessionMinutes:'Session lengths',dailyBudgetMinutes:'Daily allowance',dayReset:'Daily reset',youtubeCredits:'YouTube credits',emergencyMinutes:'Emergency minutes',creditName:'Credit name',unlockLabel:'Unlock button',youtubeUnlockLabel:'YouTube button',emergencyUnlockLabel:'Emergency button'};
export const seed=()=>({settings:{waitSeconds:12,minutesPerCredit:3,sessionMinutes:[3,5,10],dailyBudgetMinutes:0,dayReset:'04:00',youtubeCredits:4,emergencyMinutes:15,creditName:'credits',unlockLabel:'Unlock',youtubeUnlockLabel:'Unlock YouTube',emergencyUnlockLabel:'Emergency unlock'},tasks:[
{id:'pushups',name:'Push-ups',verifier:'video',metric:'repetitions',unitsPerCredit:3,correctionMinimum:1,instructions:'Count complete lowering-and-raising push-up cycles. Accept imperfect framing and ordinary form when repeated body motion is visible. Ignore setup, cuts and unrelated motion.',paused:false,gpsMode:null},
{id:'plank',name:'Plank',verifier:'video',metric:'seconds',unitsPerCredit:10,correctionMinimum:1,instructions:'Measure time actively holding a plank roughly parallel to the ground. Accept imperfect framing when a held torso, hip or shoulder line is visible. Exclude setup and breaks.',paused:false,gpsMode:null},
{id:'sprint',name:'Sprint',verifier:'gps',metric:'meters',unitsPerCredit:50,correctionMinimum:1,instructions:'Measure qualifying sprint distance from GPS. Only sections at 15–35 km/h count; exclude inaccurate points, gaps and jumps.',paused:false,gpsMode:'sprint'},
{id:'walk',name:'Take a walk',verifier:'gps',metric:'meters',unitsPerCredit:100,correctionMinimum:1,instructions:'Measure walking distance from GPS. Exclude speeds above 18 km/h, stationary drift, poor readings and tracking gaps.',paused:false,gpsMode:'walk'}]});
const plain=x=>x&&typeof x==='object'&&!Array.isArray(x);
const allowed=(x,keys)=>fail(Object.keys(x).every(k=>keys.includes(k)),'Unsupported configuration field.');
const integer=(v,a,b)=>Number.isInteger(v)&&v>=a&&v<=b;
function validateSettings(s){
 allowed(s,Object.keys(labels));
 for(const [k,min,max] of [['waitSeconds',0,600],['minutesPerCredit',1,120],['dailyBudgetMinutes',0,30],['youtubeCredits',1,100],['emergencyMinutes',1,120]])fail(integer(s[k],min,max),`${labels[k]} must be ${min}–${max}.`);
 fail(Array.isArray(s.sessionMinutes)&&s.sessionMinutes.length===3&&s.sessionMinutes.every(n=>integer(n,1,120)),'Choose three session lengths from 1 to 120 minutes.');
 fail(/^([01]\d|2[0-3]):[0-5]\d$/.test(s.dayReset),'Reset times must be HH:mm.');
 for(const k of ['creditName','unlockLabel','youtubeUnlockLabel','emergencyUnlockLabel'])fail(validText(s[k],60),'Names and button labels must contain 1–60 characters.');
}
function validText(value,maximum){return typeof value==='string'&&value.trim().length>0&&value.length<=maximum;}
function validTaskMethod(t){
 if(t.verifier==='video')return ['repetitions','seconds'].includes(t.metric)&&(t.gpsMode===null||t.gpsMode===undefined);
 return t.verifier==='gps'&&t.metric==='meters'&&['walk','sprint'].includes(t.gpsMode);
}
function validateTask(t,ids){
 fail(plain(t),'Task must be an object.');allowed(t,['id','name','verifier','metric','unitsPerCredit','correctionMinimum','instructions','paused','gpsMode']);
 fail(typeof t.id==='string'&&/^[a-z][a-z0-9_-]{0,63}$/.test(t.id)&&!ids.has(t.id),'Task IDs must be unique lowercase identifiers.');ids.add(t.id);
 fail(validText(t.name,80),'Task name must contain 1–80 characters.');
 fail(validTaskMethod(t),'Choose video with repetitions/seconds, or GPS with walking/sprint meters.');
 fail(integer(t.unitsPerCredit,1,100000)&&integer(t.correctionMinimum,1,100000),'Reward and correction minimum must be positive whole numbers.');
 fail(validText(t.instructions,2000),'Describe what the verifier should measure (up to 2,000 characters).');
 if(t.verifier==='video')fail(t.correctionMinimum<=(t.metric==='seconds'?30:150),'Video correction minimum must be achievable within 30 seconds.');
 fail(typeof t.paused==='boolean','Paused must be true or false.');
}
export function validateConfig(c){
 fail(plain(c)&&plain(c.settings)&&Array.isArray(c.tasks),'Settings and tasks required.');allowed(c,['settings','tasks']);
 validateSettings(c.settings);
 fail(c.tasks.length<=50,'Up to 50 tasks are supported.');
 const ids=new Set();for(const task of c.tasks)validateTask(task,ids);
 return clone(c);
}
export const taskSchema={type:'object',properties:{id:{type:'string'},name:{type:'string'},verifier:{type:'string',enum:['video','gps']},metric:{type:'string',enum:['repetitions','seconds','meters']},unitsPerCredit:{type:'integer'},correctionMinimum:{type:'integer'},instructions:{type:'string'},paused:{type:'boolean'},gpsMode:{type:['string','null'],enum:['walk','sprint',null]}},required:['id','name','verifier','metric','unitsPerCredit','correctionMinimum','instructions','paused','gpsMode'],additionalProperties:false};
export const configSchema={type:'object',properties:{settings:{type:'object',properties:Object.fromEntries(Object.keys(labels).map(k=>[k,k==='sessionMinutes'?{type:'array',items:{type:'integer'}}:k==='dailyBudgetMinutes'?{type:'integer',minimum:0,maximum:30}:['waitSeconds','minutesPerCredit','youtubeCredits','emergencyMinutes'].includes(k)?{type:'integer'}:{type:'string'}])),required:Object.keys(labels),additionalProperties:false},tasks:{type:'array',items:taskSchema}},required:['settings','tasks'],additionalProperties:false};
export function diff(before,after){
 const cards=[];
 for(const key of Object.keys(labels))if(!equal(before.settings[key],after.settings[key]))cards.push({id:`setting:${key}`,kind:'setting',key,title:labels[key],before:clone(before.settings[key]),after:clone(after.settings[key])});
 for(const key of new Set([...before.tasks,...after.tasks].map(t=>t.id))){const a=before.tasks.find(t=>t.id===key)||null,b=after.tasks.find(t=>t.id===key)||null;if(!equal(a,b))cards.push({id:`task:${key}`,kind:'task',key,title:(b||a).name,before:clone(a),after:clone(b)});}
 return cards;
}
export function applyCards(config,cards){const result=clone(config);for(const c of cards){if(c.kind==='setting')result.settings[c.key]=clone(c.after);else{const index=result.tasks.findIndex(t=>t.id===c.key);if(c.after===null){if(index>=0)result.tasks.splice(index,1);}else if(index>=0)result.tasks[index]=clone(c.after);else result.tasks.push(clone(c.after));}}return validateConfig(result);}
const resetCache=new Map(),formatters=new Map();
function formatterFor(timeZone){
 let formatter;try{formatter=formatters.get(timeZone)||new Intl.DateTimeFormat('en-CA',{timeZone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'});formatters.set(timeZone,formatter);}catch{throw new AppError('Use a valid IANA time zone.');}
 return formatter;
}
export function periodKey(time,reset,timeZone='UTC'){
 const formatter=formatterFor(timeZone);
 const partsAt=instant=>Object.fromEntries(formatter.formatToParts(new Date(instant)).map(p=>[p.type,p.value])),parts=partsAt(time),dateKey=`${parts.year}-${parts.month}-${parts.day}`,cacheKey=timeZone+'|'+dateKey+'|'+reset;
 let boundary=resetCache.get(cacheKey);const midnight=Date.UTC(+parts.year,+parts.month-1,+parts.day);
 if(boundary===undefined){const[h,m]=reset.split(':').map(Number),target=h*60+m;
  // Search absolute time: first repeated occurrence wins; skipped local times advance to first valid minute.
  for(let candidate=midnight-16*3600000;candidate<=midnight+40*3600000;candidate+=60000){const p=partsAt(candidate);if(`${p.year}-${p.month}-${p.day}`===dateKey&&+p.hour*60+(+p.minute)>=target){boundary=candidate;break;}}
  fail(boundary!==undefined,'Could not resolve the daily reset.');if(resetCache.size>1000)resetCache.clear();resetCache.set(cacheKey,boundary);
 }
 return new Date(time<boundary?midnight-86400000:midnight).toISOString().slice(0,10);
}
export const isCancel=s=>typeof s==='string'&&/^(?:(?:please|wait|actually|okay|ok)[\s,]+)*(?:cancel|undo|revert)(?:\s+(?:that|it|this|the\s+(?:last\s+)?change|my\s+last\s+change|all(?:\s+changes)?))?(?:\s+please)?[.!?]*$/i.test(s.trim())||typeof s==='string'&&/^(?:never mind|nevermind)[.!]*$/i.test(s.trim());
export const isApproval=s=>typeof s==='string'&&/^(?:(?:yes|yeah|yep|sure|okay|ok)[\s,]*)?(?:(?:please\s+)?(?:I\s+)?(?:apply|confirm|approve|save)(?:\s+(?:it|that|this|them|all|all changes|the changes|the change))?(?:\s+please)?)?[.!]*$/i.test(s.trim())&&/\b(?:yes|yeah|yep|sure|okay|ok|apply|confirm|approve|save)\b/i.test(s);

export function isExplicitCancellation(message){if(isCancel(message))return true;if(typeof message!=='string')return false;return /^(?:(?:please|wait|actually|okay|ok)[\s,]+)*(?:undo|revert|cancel)\s+[^.!?]{1,180}[.!]?$/i.test(message.trim())&&!/\b(?:if|would|could|mean|means|button|what|how|don't|not)\b/i.test(message);}
