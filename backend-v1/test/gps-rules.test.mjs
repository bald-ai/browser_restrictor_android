import test from 'node:test';
import assert from 'node:assert/strict';
import {verifyGps,distance} from '../evidence.mjs';
const start=100000;
const point=(longitude,time=0,other={})=>({latitude:0,longitude,time:start+time,accuracy:0,...other});
const verify=(mode,points,endedAt=start+16000)=>verifyGps({startedAt:start,task:{gpsMode:mode}},{points,endedAt});

test('GPS validates coordinates, mock flags, timestamps and recording bounds before granting distance',()=>{
 for(const replacement of [{latitude:91},{latitude:NaN},{longitude:181},{longitude:Infinity},{time:NaN},{accuracy:NaN}])assert.throws(()=>verify('walk',[point(0,0,replacement)]),/Invalid GPS position/);
 for(const field of ['mock','isMock'])assert.throws(()=>verify('walk',[point(0,0,{[field]:true})]),/Mock GPS/);
 for(const time of [-1001,17001])assert.throws(()=>verify('walk',[point(0,time)]),/chronological/);
 assert.throws(()=>verify('walk',[point(0),point(.00003)]),/chronological/);
 assert.throws(()=>verify('walk',[],start-1),/four hours/);
 assert.throws(()=>verify('walk',[],start+14401001),/four hours/);
 assert.throws(()=>verify('walk',Array(60001).fill(point(0))),/60,000/);
 assert.equal(verify('walk',[],start+14401000).fallbackEligible,true);
});

test('GPS movement policy rejects vehicle and stationary intervals with honest diagnostics',()=>{
 for(const speed of [.49,5.01]){
  const result=verify('walk',[point(0),point(.00003,1000,{speed}),point(.00006,2000,{speed:.5})]);
  assert.equal(result.measuredUnits,3.3);assert.equal(result.diagnostics.speedExcluded,speed>5?1:0);assert.equal(result.fallbackEligible,false);
 }
 assert.equal(verify('walk',[point(0),point(.00005,1000,{speed:1})]).measuredUnits,0);
 for(const speed of [null,-1,NaN,Infinity,.5,5])assert.equal(verify('walk',[point(0),point(.00003,1000,{speed})]).measuredUnits,3.3);
});

test('GPS sprint requires both position and valid device speeds inside inclusive limits',()=>{
 for(const speed of [undefined,15/3.6,35/3.6])assert.equal(verify('sprint',[point(0),point(.00005,1000,{speed})]).measuredUnits,5.6);
 for(const speed of [4.16,9.73])assert.equal(verify('sprint',[point(0),point(.00005,1000,{speed})]).measuredUnits,0);
 assert.equal(verify('sprint',[point(0),point(.00003,1000,{speed:5})]).measuredUnits,0);
 assert.equal(verify('sprint',[point(0),point(.0001,1000)]).measuredUnits,0);
});

test('GPS noise accumulates, rejected readings break continuity, and long gaps do not earn distance',()=>{
 assert.equal(verify('walk',[point(0),point(.00001,1000),point(.00003,3000)]).measuredUnits,3.3);
 for(const accuracy of [-1,25.01]){
  const r=verify('walk',[point(0),point(.00003,1000,{accuracy}),point(.00006,2000)]);assert.equal(r.measuredUnits,0);assert.equal(r.diagnostics.rejected,1);
 }
 assert.equal(verify('walk',[point(0,0,{accuracy:25}),point(.00018,5000,{accuracy:25})]).measuredUnits,20);
 const gap=verify('walk',[point(0),point(.00003,15001)]);assert.equal(gap.measuredUnits,0);assert.equal(gap.diagnostics.gaps,1);
 assert.equal(verify('walk',[point(0),point(.00003,15000)]).measuredUnits,3.3);
 assert(Math.abs(distance(point(0),point(1))-111195)<.1);
});


test('bad timestamps after earned motion invalidate the entire upload, including inaccurate and noise fixes',()=>{
 for(const accuracy of [0,26])for(const time of [1999,2000]){
  const points=[point(0),point(.00003,1000),point(.000031,2000,{accuracy}),point(.00006,time),point(.00009,4000)];
  assert.throws(()=>verify('walk',points),/chronological/);
 }
 assert.throws(()=>verify('walk',[point(0),point(.00003,1000),point(.00006,2000,{isMock:true}),point(.00009,3000)]),/Mock GPS/);
});

test('legacy GPS mode fallback still enforces the maximum inferred speed',()=>{
 assert.equal(verify(null,[point(0),point(.00005,1000)]).measuredUnits,5.6);
 assert.equal(verify(null,[point(0),point(.0001,1000)]).measuredUnits,0);
});
