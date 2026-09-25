import test from 'node:test';
import assert from 'node:assert/strict';
import {seed,validateConfig,AppError} from '../domain.mjs';

function rejected(change,message){
 const input=seed();change(input);const before=structuredClone(input);
 assert.throws(()=>validateConfig(input),error=>error instanceof AppError&&message.test(error.message));
 assert.deepEqual(input,before,'validation must not partially mutate the supplied configuration');
}

test('configuration bounds include both endpoints and reject fractional, missing and out-of-range values',()=>{
 for(const [key,min,max] of [['waitSeconds',0,600],['minutesPerCredit',1,120],['dailyBudgetMinutes',0,30],['youtubeCredits',1,100],['emergencyMinutes',1,120]]){
  for(const value of [min,max]){const c=seed();c.settings[key]=value;assert.deepEqual(validateConfig(c),c);}
  for(const value of [min-1,max+1,1.5,null,undefined,'1'])rejected(c=>c.settings[key]=value,/must be/);
 }
 for(const value of [null,[],[1,2],[1,2,3,4],[0,2,3],[1,2,121]])rejected(c=>c.settings.sessionMinutes=value,/three session/);
 for(const value of ['24:00','4:00','12:60'])rejected(c=>c.settings.dayReset=value,/HH:mm/);
 for(const key of ['creditName','unlockLabel','youtubeUnlockLabel','emergencyUnlockLabel']){
  for(const value of ['', ' ', 'x'.repeat(61), 123])rejected(c=>c.settings[key]=value,/1–60/);
 }
});

test('task definitions reject incompatible evidence, oversized text, duplicates and impossible video corrections',()=>{
 const invalid=[
  [{id:'1bad'},/identifiers/],[{id:'a'.repeat(65)},/identifiers/],[{id:5},/identifiers/],
  [{name:''},/Task name/],[{name:'a'.repeat(81)},/Task name/],[{name:1},/Task name/],
  [{verifier:'unknown'},/Choose video/],[{metric:'meters'},/Choose video/],[{gpsMode:'walk'},/Choose video/],
  [{verifier:'gps',metric:'meters',gpsMode:null},/Choose video/],
  [{verifier:'gps',metric:'seconds',gpsMode:'walk'},/Choose video/],
  [{unitsPerCredit:0},/positive whole/],[{unitsPerCredit:100001},/positive whole/],
  [{correctionMinimum:0},/positive whole/],[{correctionMinimum:100001},/positive whole/],
  [{correctionMinimum:151},/achievable/],[{metric:'seconds',correctionMinimum:31},/achievable/],
  [{instructions:''},/Describe/],[{instructions:'i'.repeat(2001)},/Describe/],
  [{paused:1},/Paused/],[{extra:true},/Unsupported/],
 ];
 for(const [patch,message]of invalid)rejected(c=>Object.assign(c.tasks[0],patch),message);
 rejected(c=>c.tasks.push(c.tasks[0]),/unique/);
 rejected(c=>c.tasks=Array.from({length:51},(_,i)=>({...c.tasks[0],id:'task'+i})),/50 tasks/);
 rejected(c=>c.tasks[0]=null,/object/);
 for(const patch of [{correctionMinimum:150},{metric:'seconds',correctionMinimum:30},{gpsMode:undefined},{id:'a'.repeat(64),name:'n'.repeat(80),instructions:'i'.repeat(2000)}]){
  const c=seed();Object.assign(c.tasks[0],patch);assert.deepEqual(validateConfig(c),c);
 }
 const c=seed();Object.assign(c.tasks[3],{unitsPerCredit:100000,correctionMinimum:100000});assert.deepEqual(validateConfig(c),c);
});

test('configuration validation requires objects and returns an independent accepted value',()=>{
 for(const invalid of [null,[],{}, {settings:null,tasks:[]},{settings:{},tasks:null}])assert.throws(()=>validateConfig(invalid),/Settings and tasks/);
 rejected(c=>c.extra=true,/Unsupported/);rejected(c=>c.settings.extra=true,/Unsupported/);
 const c=seed(),copy=validateConfig(c);copy.settings.waitSeconds=0;copy.tasks[0].name='Changed';
 assert.equal(c.settings.waitSeconds,12);assert.equal(c.tasks[0].name,'Push-ups');
});
