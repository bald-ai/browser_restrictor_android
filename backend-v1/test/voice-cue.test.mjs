import test from 'node:test';
import assert from 'node:assert/strict';
import {createVoiceCue} from '../public/voice-cue.js';

test('voice cue schedules two quiet notes only after audio is running and releases nodes',()=>{
 const notes=[],gains=[];let resumes=0,closes=0,instances=0;
 class Context{
  constructor(){instances++;this.state='running';this.currentTime=10;this.destination={};}
  resume(){resumes++;return Promise.resolve();}close(){closes++;return Promise.resolve();}
  createOscillator(){const note={frequency:{},connect(target){this.target=target;},start(at){this.startAt=at;},stop(at){this.stopAt=at;},disconnect(){this.disconnected=true;}};notes.push(note);return note;}
  createGain(){const calls=[];const gain={calls,gain:{setValueAtTime:(...a)=>calls.push(['set',...a]),linearRampToValueAtTime:(...a)=>calls.push(['linear',...a]),exponentialRampToValueAtTime:(...a)=>calls.push(['exponential',...a])},connect(){},disconnect(){this.disconnected=true;}};gains.push(gain);return gain;}
 }
 const cue=createVoiceCue(Context);cue.play();assert.equal(notes.length,0);cue.prepare();cue.prepare();assert.equal(instances,1);assert.equal(resumes,2);cue.play();
 assert.deepEqual(notes.map(n=>[n.type,n.frequency.value,n.startAt,n.stopAt]),[['sine',660,10,10.15],['sine',880,10.11,10.26]]);
 assert.deepEqual(gains[0].calls,[['set',0,10],['linear',.07,10.015],['exponential',.001,10.14]]);
 for(const [i,note]of notes.entries()){assert.equal(note.target,gains[i]);note.onended();assert.equal(note.disconnected,true);assert.equal(gains[i].disconnected,true);}
 cue.dispose();cue.dispose();cue.play();assert.equal(closes,1);assert.equal(notes.length,2);
});

test('unsupported, suspended or broken audio never prevents a voice call',async()=>{
 for(const Type of [null,class{constructor(){throw Error('unavailable');}},class{state='suspended';resume(){return Promise.reject(Error('denied'));}close(){return Promise.reject(Error('closed'));}},class{state='running';resume(){throw Error('resume');}createOscillator(){throw Error('audio');}close(){throw Error('close');}}]){
  const cue=createVoiceCue(Type);assert.doesNotThrow(()=>{cue.prepare();cue.play();cue.dispose();});
 }
 await new Promise(resolve=>setImmediate(resolve));
});
