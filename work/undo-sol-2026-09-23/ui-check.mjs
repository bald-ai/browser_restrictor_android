import {chromium} from '../../prototypes/settings-speed-lab/node_modules/playwright/index.mjs';
import {createApp} from '../../backend-v1/server.mjs';
import assert from 'node:assert/strict';import fs from 'node:fs/promises';import os from 'node:os';import path from 'node:path';
const dataDir=await fs.mkdtemp(path.join(os.tmpdir(),'friction-undo-ui-'));const app=await createApp({dataDir});await new Promise(r=>app.server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true,channel:'chrome'});
try{
 const page=await browser.newPage({viewport:{width:412,height:915}});const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto(`http://127.0.0.1:${app.server.address().port}`);await page.locator('#setupTab').click();await page.locator('#assistantModel option').first().waitFor({state:'attached'});
 await page.locator('#assistantModel').selectOption('gpt-6-sol');await page.locator('#assistantReasoning').selectOption('low');await page.locator('#assistantSpeed').selectOption('fast');await page.locator('#saveAssistant').click();await page.getByText('Saved. Applies to your next request.').waitFor();
 const api=(url,body)=>page.evaluate(async({url,body})=>{const {api}=await import('/app.js');return api(url,body);},{url,body});
 const before=await api('/state');const stage=async changes=>{const s=await api('/state');return api('/proposals',{changes,expectedRevision:s.revision,title:'Undo test'});};
 // Stage through the public API, then inspect the real rendered controls.
 let p=await stage([{action:'set_setting',field:'waitSeconds',value:9}]);p=p.proposal||p;await api(`/proposals/${p.id}/confirm`,{version:p.version});
 let q=await stage([{action:'remove_task',taskId:'sprint'}]);q=q.proposal||q;await api(`/proposals/${q.id}/confirm`,{version:q.version});await page.reload();await page.locator('#undoChanges').waitFor({state:'attached'});await page.locator('#talkTab').click();await page.locator('#reviewDetails > summary').click();
 assert.equal(await page.locator('[data-undo]').count(),0);assert.equal(await page.locator('[data-undo-request]').count(),0);assert.equal(await page.locator('#undoChanges').count(),1);
 await page.locator('#undoChanges').click();await page.waitForFunction(()=>!document.querySelector('#undoChanges'));const after=await api('/state');assert.deepEqual(after.config.tasks,before.config.tasks);assert.equal(after.config.settings.waitSeconds,9);assert.equal(after.undoableChangeId,null);
 await page.reload();assert.equal(await page.locator('[data-undo], [data-undo-request], #undoChanges').count(),0);await page.locator('#setupTab').click();assert.equal(await page.locator('#assistantModel').inputValue(),'gpt-6-sol');assert.equal(await page.locator('#assistantReasoning').inputValue(),'low');assert.deepEqual(errors,[]);await page.screenshot({path:'work/undo-sol-2026-09-23/ui.png'});console.log('PASS: latest-only whole-action undo, original task order, no undo chain, Sol 6 Low persistence.');
}finally{await browser.close();app.server.closeAllConnections();await new Promise(r=>app.server.close(r));await fs.rm(dataDir,{recursive:true,force:true});}
