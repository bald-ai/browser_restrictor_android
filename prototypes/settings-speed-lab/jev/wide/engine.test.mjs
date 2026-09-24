import test from 'node:test';
import assert from 'node:assert/strict';
import {seed} from '../../../../backend-v1/domain.mjs';
import {build,decode,candidates} from './engine.mjs';
const choice=(value,p=1)=>({type:'choice',choice:value,probabilities:{[value]:p}});
function fixture(message){const config=seed(),b=build({config,message},'v2'),answers=Object.fromEntries(b.targets.map(t=>[t.key,choice('unchanged')]));answers.route=choice('jev');return{config,b,answers};}
function pick(f,key,value){const t=f.b.targets.find(t=>t.key===key),index=t.values.findIndex(v=>JSON.stringify(v)===JSON.stringify(value));assert(index>=0);f.answers[key]=choice('v'+index);}
test('partial supported result rejected when entire request routes elsewhere',()=>{const f=fixture('Set wait to 18 seconds and write instructions.');pick(f,'waitSeconds',18);f.answers.route=choice('general');assert.equal(decode(f.answers,f.b,f.config).accepted,false);});
test('missing and low-confidence fields cannot silently disappear',()=>{const f=fixture('Set wait to 18 seconds');pick(f,'waitSeconds',18);delete f.answers.walk_paused;assert.equal(decode(f.answers,f.b,f.config).accepted,false);f.answers.walk_paused=choice('unchanged',.7);assert.equal(decode(f.answers,f.b,f.config).accepted,false);});
test('current app validation rejects incompatible metric edits',()=>{const f=fixture('Measure walking in seconds');pick(f,'walk_metric','seconds');assert.equal(decode(f.answers,f.b,f.config).reason,'validation');});
test('large exact values, units, clock and relative candidates',()=>{const c=candidates('Wait 3 minutes, walk 1.5 kilometers, reset at 11:45 pm, presets 6, 12 and 24',seed(),'v2');assert(c.nums.includes(180));assert(c.nums.includes(1500));assert(c.times.includes('23:45'));assert(c.triples.some(v=>JSON.stringify(v)==='[6,12,24]'));assert(c.nums.includes(6));});
test('fixed edit preserves every unrelated setting and task',()=>{const f=fixture('Set wait to 18 seconds');pick(f,'waitSeconds',18);const result=decode(f.answers,f.b,f.config),expected=seed();expected.settings.waitSeconds=18;assert.deepEqual(result.config,expected);});
test('unknown choice cannot inject value',()=>{const f=fixture('Set wait to 18 seconds');f.answers.waitSeconds=choice('v9999');assert.equal(decode(f.answers,f.b,f.config).accepted,false);});
test('no-op recognized without staging an empty review',()=>{const f=fixture('Set wait to 12 seconds');pick(f,'waitSeconds',12);assert.equal(decode(f.answers,f.b,f.config).noOp,true);});
test('AM/PM candidate does not also offer the unconverted wall clock',()=>{assert.deepEqual(candidates('Reset at 11:45 pm',seed(),'v4').times,['23:45']);assert.deepEqual(candidates('Reset at 12:20 am',seed(),'v4').times,['00:20']);assert.deepEqual(candidates('Reset at 06:30',seed(),'v4').times,['06:30']);});
