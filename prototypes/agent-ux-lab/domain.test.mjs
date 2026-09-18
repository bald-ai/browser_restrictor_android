import test from 'node:test';
import assert from 'node:assert/strict';
import {seed,validateConfig,estimate} from './domain.mjs';
test('accepts editable task and reward configuration without mutating caller',()=>{const c=seed();c.settings.waitSeconds=0;c.tasks.push({...c.tasks[1],id:'teeth',name:'Brush teeth',unitsPerCredit:120});const v=validateConfig(c);v.tasks[0].name='changed';assert.equal(c.tasks[0].name,'Push-ups');});
test('rejects unsupported verifiers and incompatible measurements',()=>{for(const [verifier,metric] of [['self','seconds'],['gps','repetitions'],['video','meters']]){const c=seed();Object.assign(c.tasks[0],{verifier,metric});assert.throws(()=>validateConfig(c));}});
test('requires correction evidence, unique identities, and daily resets',()=>{for(const mutate of [c=>c.tasks[0].correctionMinimum=0,c=>c.tasks[1].id=c.tasks[0].id,c=>c.settings.dayReset=null,c=>c.settings.budgetReset='25:00']){const c=seed();mutate(c);assert.throws(()=>validateConfig(c));}});
test('cost discounts cached input and includes output reasoning only once',()=>{const usage={input_tokens:10000,input_tokens_details:{cached_tokens:8000},output_tokens:500,output_tokens_details:{reasoning_tokens:100}};const c=estimate(usage,'gpt-5.6-terra');assert.equal(c.usd,.0116);assert.equal(c.withCacheWriteAllowance,.0126);assert.equal(c.reasoningTokens,100);assert.equal(estimate(null,'gpt-5.6-terra'),null);});

test('rejects attempts to add earned balances to editable state',()=>{const c=seed();c.balanceMinutes=1000;assert.throws(()=>validateConfig(c));});

test('uses reported cache writes and Astra rates without double-counting input',()=>{const c=estimate({input_tokens:1000,input_tokens_details:{cached_tokens:600,cache_write_tokens:300},output_tokens:100},'gpt-6-astra');assert.equal(c.usd,.01035);assert.equal(c.withCacheWriteAllowance,c.usd);});

test('Astra fast pricing doubles standard token and cache-write cost',()=>{
 const usage={input_tokens:1000,input_tokens_details:{cached_tokens:200,cache_write_tokens:300},output_tokens:100};
 const standard=estimate(usage,'gpt-6-astra');const fast=estimate(usage,'gpt-6-astra','priority');
 assert.equal(fast.usd,standard.usd*2);assert.equal(fast.withCacheWriteAllowance,standard.withCacheWriteAllowance*2);
 assert.equal(fast.inputTokens,standard.inputTokens);
});

test('Sol fast rate includes cache writes at double standard pricing',()=>{
 const usage={input_tokens:1000,input_tokens_details:{cached_tokens:200,cache_write_tokens:300},output_tokens:100};
 const cost=estimate(usage,'gpt-5.6-sol','priority');
 assert.ok(Math.abs(cost.usd-0.01116)<1e-10);
});
