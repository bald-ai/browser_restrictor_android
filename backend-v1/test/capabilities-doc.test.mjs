import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {configSchema,taskSchema} from '../domain.mjs';
import {changeSchema} from '../edit-schema.mjs';
import {tools} from '../prompts.mjs';

const doc=await fs.readFile(new URL('../../AGENT_CAPABILITIES.md',import.meta.url),'utf8');
const rows=section=>[...section.matchAll(/^\| `([^`]+)` \|/gm)].map(m=>m[1]).sort();
const section=(start,end)=>doc.split(start)[1]?.split(end)[0]||'';
test('capability reference inventories match current agent schemas',()=>{
 assert.deepEqual(rows(section('## Editable settings','## Task controls')),Object.keys(configSchema.properties.settings.properties).sort());
 assert.deepEqual(rows(section('## Task controls','## Callable agent functions')),Object.keys(taskSchema.properties).sort());
 assert.deepEqual(rows(section('These five functions','Operations accepted')),tools.map(t=>t.name).sort());
 assert.deepEqual(rows(section('Operations accepted','Example:')),[...new Set(changeSchema.anyOf.map(op=>op.properties.action.enum[0]))].sort());
});
test('coding-agent instructions require reading and maintaining the capability reference',async()=>{
 const instructions=await fs.readFile(new URL('../../AGENTS.md',import.meta.url),'utf8');
 assert.match(instructions,/\[AGENT_CAPABILITIES\.md\]\(AGENT_CAPABILITIES\.md\)/);
 assert.match(instructions,/update `AGENT_CAPABILITIES\.md` in the\nsame change/);
});
