import test from 'node:test';
import assert from 'node:assert/strict';
import {readEvents,startLiveUpdates} from '../public/live-updates.js';
const pause=ms=>new Promise(r=>setTimeout(r,ms));
test('event parser handles split UTF-8, CRLF, heartbeat and multiple frames',async()=>{
 const bytes=new TextEncoder().encode(': ping\r\n\r\ndata: {"kind":"proposal","title":"Čas"}\r\n\r\ndata: bad\n\ndata: {"kind":"applied"}\n\n');
 const events=[];await readEvents(new ReadableStream({start(c){for(const b of bytes)c.enqueue(new Uint8Array([b]));c.close();}}),e=>events.push(e));
 assert.deepEqual(events,[{kind:'proposal',title:'Čas'},{kind:'applied'}]);
});
test('event connection authenticates, refreshes on reconnect, and stops cleanly',async()=>{
 let calls=0,updates=0,signal;
 const connection=startLiveUpdates({url:'/api/v1/events',headers:{Authorization:'Bearer test-token'},retryMs:1,idleMs:10000,onUpdate:()=>updates++,fetcher:async(url,opts)=>{
  assert.equal(url,'/api/v1/events');assert.equal(opts.headers.Authorization,'Bearer test-token');calls++;signal=opts.signal;
  return{ok:true,body:new ReadableStream({start(c){if(calls===1)c.close();else opts.signal.addEventListener('abort',()=>c.error(Error('Aborted')));}})};
 }});
 for(let i=0;i<100&&calls<2;i++)await pause(2);
 assert.equal(calls,2);assert.equal(updates,2);assert(connection.isConnected());connection.stop();assert(signal.aborted);await pause(10);assert.equal(calls,2);assert(!connection.isConnected());
});
test('a stalled stream is aborted and retried',async()=>{
 let calls=0;
 const connection=startLiveUpdates({url:'/api/v1/events',headers:{},retryMs:1,idleMs:10,onUpdate:()=>{},fetcher:async(url,{signal})=>{calls++;return{ok:true,body:new ReadableStream({start(c){signal.addEventListener('abort',()=>c.error(Error('Aborted')));}})};}});
 for(let i=0;i<100&&calls<2;i++)await pause(2);connection.stop();assert(calls>=2);
});

test('oversized event frames reject and release the stream even if cancellation fails',async()=>{
 let released=false,cancelled=false,reads=0;
 const body={getReader:()=>({read:async()=>++reads===1?{value:new TextEncoder().encode('x'.repeat(1000001)),done:false}:{done:true},cancel:async()=>{cancelled=true;throw Error('already closed');},releaseLock:()=>{released=true;}})};
 await assert.rejects(readEvents(body,()=>assert.fail('no frame may be dispatched')),/frame too large/);
 assert.equal(cancelled,true);assert.equal(released,true);
});

test('failed responses reconnect without reporting connected and stop cancels retry',async()=>{
 for(const response of [{ok:false,body:{}},{ok:true,body:null}]){
  let calls=0,updates=0;
  const connection=startLiveUpdates({url:'/events',headers:{},retryMs:1000,onUpdate:()=>updates++,fetcher:async()=>{calls++;return response;}});
  await pause(5);assert.equal(connection.isConnected(),false);assert.equal(updates,0);connection.stop();await pause(5);assert.equal(calls,1);
 }
});
