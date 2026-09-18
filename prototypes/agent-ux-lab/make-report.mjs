import fs from 'node:fs/promises';
import {estimate} from './domain.mjs';
const s=JSON.parse(await fs.readFile('state.json','utf8'));
const probe=estimate({input_tokens:6417,output_tokens:6},'gpt-5.6-terra');
const usd=n=>'$'+n.toFixed(4);
const known=s.turns.reduce((n,t)=>n+(t.cost?.usd||0),0)+probe.usd;
const upper=s.turns.reduce((n,t)=>n+(t.cost?.withCacheWriteAllowance||0),0)+probe.withCacheWriteAllowance;
const voice=s.voices.reduce((n,v)=>n+v.seconds*.05/60,0);
const labels=['Set wait to 5 sec','Rename selected walk to Fresh air','Voice: set wait to 8 sec','Add brushing (tool service failed)','Retry brushing + generate artifact','Explain YouTube (HTTP 500)','Retry explanation (HTTP 500)'];
const report=`# Voice + hosted agent experiment — 16 September 2026

## Result

A working local fake friction app, connected to real OpenAI Agents API and GPT-Live. Two hosted-agent conversations, seven app requests (including retries), one voice session, and one small initial connection probe.

Verified changes: wait 12 → 5 → 8 seconds; selected walk renamed Fresh air; a new Brush teeth video task with 30 active seconds per credit and a 1-second correction minimum. The original four-minute sample balance was preserved. The browser gate used the saved eight-second rule and completed successfully.

The hosted agent wrote and checked configuration code. Its published app-config.json was downloaded from the Artifacts API and compared exactly against the live app state; they matched.

## Recorded costs

| Component | Recorded estimate |
| --- | ---: |
| Agent model tokens, including connection probe | ${usd(known)}–${usd(upper)} |
| GPT-Live: final billed duration ${s.voices[0].seconds} seconds | ${usd(voice)} |
| **Known subtotal** | **${usd(known+voice)}–${usd(upper+voice)}** |
| Successful voice-triggered agent turn | Usage unavailable; additional unknown cost |
| Two HTTP 500 requests | Usage unavailable; charge, if any, unknown |
| Three OpenAI-hosted environments, including probe | Additional container charges, not returned in turn usage |

**This subtotal is incomplete and is not the final bill.** The range allows for cache writes on the tokens actually reported. Missing usage is never counted as a known zero. Published container prices start at $0.03 for a 1 GB / 20-minute session; the API responses did not expose the actual environment size or billed charge, so no assumed container amount is included.

Model prices used, per million tokens: Terra $2 input / $0.20 cached / $12 output; Sol $4 / $0.40 / $20. The upper column adds 25% to uncached input cost to allow for cache writes. Voice costs $0.05/minute, billed per second. Rates checked against [OpenAI pricing](https://developers.openai.com/api/docs/pricing). [Agents usage is best-effort](https://developers.openai.com/api/docs/guides/agents-api/observability), may be null, and does not expose cache-write counts.

| Request | Model | Seconds to finish | Reported model cost |
| --- | --- | ---: | ---: |
${s.turns.map((t,i)=>`| ${labels[i]||t.message.replaceAll('|','/')} | ${t.model} | ${(t.elapsedMs/1000).toFixed(1)} | ${t.cost?usd(t.cost.usd)+'–'+usd(t.cost.withCacheWriteAllowance):'Unavailable'} |`).join('\n')}

The initial connection probe separately used 6,417 input and 6 output tokens: ${usd(probe.usd)}–${usd(probe.withCacheWriteAllowance)}. It is included in the subtotal above.

## Voice evidence

Input speech was synthesized locally with macOS say, then streamed as 24 kHz mono PCM through the same local WebSocket route used by the browser microphone. It was not submitted as text to GPT-Live.

User: “Please change the waiting time to eight seconds.”

GPT-Live: “Alright, checkin’ that now.” → client delegation → hosted agent read_app → sandbox code → apply_config → GPT-Live: “Done, the waiting time is now eight seconds.”

The agent portion completed in 32.7 seconds. The test kept the voice connection open for 100 billable seconds, including silence and time waiting for the agent. The original audio and event log are preserved. The short highlight removes silence; it does not change speech.

- [Voice highlight](voice-demo-highlight.wav)
- [Full voice output](voice-${s.voices[0].id}.wav)
- [Voice event log](voice-test-events.json)
- [Downloaded agent-generated configuration](agent-generated-app-config.json)

This tests live audio input, transcription, delegation, agent actions and synthesized reply. Actual browser microphone hardware was not exercised in the automated test. The page includes microphone capture and playback for manual use.

## Failures and limits

- Sol's first task-creation attempt failed inside OpenAI's tool connection with “no active turn.” No app change was applied. Retrying in the same conversation succeeded. The failed attempt's model cost is included.
- Two later read-only statistics requests returned OpenAI HTTP 500. They did not produce answers and have no reported usage. No more automatic paid retries were run.
- Successful configuration changes took 31.9–66.9 seconds end to end. This is a tiny sample, not a model benchmark. The first call includes environment setup; follow-ups reuse context.
- The demo mandates sandbox coding for every edit so the user can see it. Those extra steps contribute to latency and cost. The experiment does not establish that every production setting change needs code execution.
- Agent-controlled configuration is validated by fixed app code. Video/GPS verifiers, balances, analytics and blocking are simulated. This does not validate Android integration or cheating resistance.

## Verification

Five local tests pass: valid editable configuration, incompatible verifier rejection, mandatory resets/correction evidence/unique IDs, cached-token accounting, and rejection of balance injection. Browser testing verified card context, changed rules, task creation, gate countdown, audio recording controls and no console warnings/errors during the inspected session. The downloaded sandbox JSON matched app state exactly.

Test sandboxes were deleted after use. The local demo server remains available for manual experiments. New sandboxes automatically close after five idle minutes. No paid conversation runs until the user sends a message or starts voice.

## Files

App: ../index.html, ../app.js, ../style.css
Backend: ../server.mjs
Configuration/rates: ../domain.mjs
Reproduction: ../voice-experiment.mjs
Instructions: ../README.md
Raw measurements: measurement-snapshot.json and session snapshots in this folder.
`;
await fs.writeFile('results/EXPERIMENT_REPORT.md',report);
await fs.writeFile('results/cost-summary.json',JSON.stringify({date:'2026-09-16',probe,knownAgentUsd:known,knownAgentWithCacheWriteAllowanceUsd:upper,voiceUsd:voice,knownSubtotalUsd:known+voice,knownSubtotalWithCacheWriteAllowanceUsd:upper+voice,unpricedRequests:s.turns.filter(t=>!t.cost).map(t=>({message:t.message,status:t.status,turnId:t.turnId})),sandboxCharges:'not included; unavailable from usage',completeBill:false},null,2));
console.log({knownSubtotal:known+voice,withCacheWriteAllowance:upper+voice,unpricedRequests:s.turns.filter(t=>!t.cost).length});
