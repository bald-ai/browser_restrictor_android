import {chromium} from '../../prototypes/settings-speed-lab/node_modules/playwright/index.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
const browser=await chromium.connectOverCDP('http://127.0.0.1:9229');
const page=browser.contexts()[0].pages().find(p=>p.url().includes('friction-v1-api'));
try{
 await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='Connected');
 const before=await page.evaluate(async()=>{const {api}=await import('/app.js');const s=await api('/state');return {revision:s.revision,voiceActive:s.voiceActive};});
 assert.equal(before.voiceActive,false,'No existing user call interrupted');
 await page.evaluate(()=>{window.phoneCue=[];const original=AudioContext.prototype.createOscillator;AudioContext.prototype.createOscillator=function(...args){window.phoneCue.push({state:this.state,at:this.currentTime});return original.apply(this,args);};});
 await page.locator('#composerVoice').click();
 await page.waitForFunction(()=>document.querySelector('#composerVoice').getAttribute('aria-label')==='End voice chat',null,{timeout:45000});
 const cue=await page.evaluate(()=>window.phoneCue);assert.equal(cue.length,2);assert(cue.every(n=>n.state==='running'));
 await page.evaluate(()=>window.scrollTo(0,document.body.scrollHeight));
 await page.waitForTimeout(500);
 const end=await page.locator('#composerVoice').boundingBox(),height=await page.evaluate(()=>innerHeight);assert(end.y>=0&&end.y+end.height<=height);
 await page.screenshot({path:'work/chat-ux-2026-09-17/phone-voice-live.png'});
 await page.locator('#composerVoice').click();
 await page.waitForFunction(()=>document.querySelector('#composerVoice').getAttribute('aria-label')==='Start voice chat');
 const after=await page.evaluate(async()=>{const {api}=await import('/app.js');const s=await api('/state');return {revision:s.revision,voiceActive:s.voiceActive};});
 assert.equal(after.voiceActive,false);assert.equal(after.revision,before.revision);
 await fs.writeFile('work/chat-ux-2026-09-17/phone-result.json',JSON.stringify({passed:true,cue,end,viewportHeight:height,before,after},null,2));
 console.log('PASS: real phone voice connection, two-tone audio scheduled in running AudioContext, bottom end button visible, button closes real call, settings revision unchanged');
}finally{await page.evaluate(()=>window.FrictionStopVoice?.()).catch(()=>{});await browser.close();}
