import {chromium} from '../../prototypes/settings-speed-lab/node_modules/playwright/index.mjs';
import assert from 'node:assert/strict';import fs from 'node:fs/promises';
const b=await chromium.connectOverCDP('http://127.0.0.1:9229');
try{
 const page=b.contexts()[0].pages().find(p=>p.url().includes('friction-v1-api-production'));assert(page);
 await page.reload();await page.locator('#setupTab').click();await page.locator('#assistantModel option[value="gpt-6-sol"]').waitFor({state:'attached'});
 const state=()=>page.evaluate(async()=>{const{api}=await import('/app.js');return api('/state');});const before=await state();assert(!before.voiceActive&&!before.busy);
 const settings={model:'gpt-6-sol',reasoning:'low',serviceTier:'fast'};
 await page.locator('#assistantModel').selectOption(settings.model);await page.locator('#assistantReasoning').selectOption(settings.reasoning);await page.locator('#assistantSpeed').selectOption(settings.serviceTier);await page.locator('#saveAssistant').click();await page.getByText('Saved. Applies to your next request.').waitFor();assert.deepEqual((await state()).assistantSettings,settings);
 await page.reload();await page.locator('#setupTab').click();await page.waitForFunction(()=>document.querySelector('#assistantModel')?.value==='gpt-6-sol');assert.equal(await page.locator('#assistantReasoning').inputValue(),'low');assert.equal(await page.locator('#assistantSpeed').inputValue(),'fast');
 const after=await state();assert.deepEqual(after.config,before.config);assert.equal(after.revision,before.revision);assert.deepEqual(after.proposals,before.proposals);assert(Object.hasOwn(after,'undoableChangeId'));assert.equal(await page.locator('[data-undo]').count(),0);
 const live=await page.evaluate(async()=>{const{api}=await import('/app.js');return api('/chat',{message:'Briefly explain what waiting time means. Do not change any settings.'});});
 const usage=live.usage.entries.findLast(e=>e.model==='gpt-6-sol');assert(usage);assert.equal(usage.reasoning,'low');assert.equal(usage.serviceTier,'fast');assert.deepEqual(live.config,before.config);assert.equal(live.revision,before.revision);
 await page.locator('#assistantHeading').scrollIntoViewIfNeeded();await page.screenshot({path:'work/undo-sol-2026-09-23/phone-settings.png'});
 const result={passed:true,settings,actualModel:usage.model,actualTier:usage.serviceTier,reasoning:usage.reasoning,reply:live.text,elapsedMs:live.elapsedMs,appRulesUnchanged:true,revision:live.revision,applicationStatus:live.applicationStatus,latestOnlyUndoLoaded:true};
 await fs.writeFile('work/undo-sol-2026-09-23/phone-result.json',JSON.stringify(result,null,2));const p='work/undo-sol-2026-09-23/status.json';const status=JSON.parse(await fs.readFile(p));status.phoneSettingsApplied=true;delete status.phoneBlocker;status.phoneVerified=result;await fs.writeFile(p,JSON.stringify(status,null,2));console.log(JSON.stringify(result));
}finally{await b.close();}
