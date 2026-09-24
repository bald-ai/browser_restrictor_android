import {chromium} from '../../prototypes/settings-speed-lab/node_modules/playwright/index.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
const browser=await chromium.connectOverCDP('http://127.0.0.1:9229');
const page=browser.contexts()[0].pages().find(p=>p.url().includes('friction-v1-api-production'));
let proposalId,restored=false,before;
const state=()=>page.evaluate(async()=>{const {api}=await import('/app.js');return api('/state');});
async function waitApplied(value,revision){const end=Date.now()+30000;while(Date.now()<end){const s=await state();if(s.config.settings.waitSeconds===value&&s.revision===revision&&s.appliedRevision===revision)return s;await new Promise(r=>setTimeout(r,300));}throw Error('Phone acknowledgement timed out');}
try{
 const health=await page.evaluate(async()=>fetch('/health').then(r=>r.json()));assert.equal(health.models.fastEdit,'typesafe-ai/jev');
 before=await state();assert.equal(before.voiceActive,false);assert(!before.busy);assert(!before.proposals.some(p=>p.status==='pending'));
 const desired=before.config.settings.waitSeconds===7?8:7;
 const responsePromise=page.waitForResponse(r=>r.url().endsWith('/api/v1/chat')&&r.request().method()==='POST',{timeout:75000});
 await page.locator('#message').fill(`Set the waiting time to exactly ${desired} seconds. Keep all other settings as they are.`);await page.locator('#send').click();
 const response=await responsePromise;assert(response.ok());const result=await response.json();console.log(JSON.stringify({elapsedMs:result.elapsedMs,trace:result.trace}));
 const review=result.proposals.findLast(p=>p.status==='pending');proposalId=review?.id;assert(review);assert(result.trace.some(t=>t.name==='jev'&&t.accepted),'Phone request must take Jev path');assert.deepEqual(result.config,before.config);
 assert.equal(review.cards.length,1);assert.equal(review.cards[0].after,desired);
 await page.locator('#applyChanges').waitFor();await page.screenshot({path:'work/jev-phone-2026-09-20/review.png'});
 await page.locator('#applyChanges').click();
 const applied=await waitApplied(desired,before.revision+1);
 await page.locator('#reviewDetails > summary').click();
 await page.locator('#undoChanges').click();
 const after=await waitApplied(before.config.settings.waitSeconds,before.revision+2);assert.deepEqual(after.config,before.config);restored=true;
 await page.screenshot({path:'work/jev-phone-2026-09-20/restored.png'});
 const report={passed:true,health,elapsedMs:result.elapsedMs,trace:result.trace,before:{revision:before.revision,waitSeconds:before.config.settings.waitSeconds},applied:{revision:applied.revision,appliedRevision:applied.appliedRevision,waitSeconds:applied.config.settings.waitSeconds},after:{revision:after.revision,appliedRevision:after.appliedRevision,waitSeconds:after.config.settings.waitSeconds},allOriginalSettingsRestored:true};
 await fs.writeFile('work/jev-phone-2026-09-20/result.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
}finally{
 if(proposalId&&!restored)await page.evaluate(async id=>{const{api}=await import('/app.js');return api('/cancel',{proposalId:id});},proposalId).catch(e=>console.error('Cleanup failed:',e.message));
 await browser.close();
}
