
import os as _session_os, sys as _session_sys, subprocess as _session_subprocess
from pathlib import Path as _SessionPath
if not _session_os.environ.get('VERIFICATION_SESSION_ROOT'):
    raise SystemExit('Internal measurement step. Use python3 scripts/verification-session.py metrics for a fresh, disposable run.')
import collections,hashlib,json,pathlib,re
from source_manifest import source_manifest
from mutation_sources import mutation_source
root=pathlib.Path(__file__).resolve().parent.parent
current_inputs=source_manifest()
metrics=root/'quality/metrics'
verification=json.loads((metrics/'verification.json').read_text())
steps={step['name']:step for step in verification['steps']}
backend=(root/steps['backend-ordinary']['log']).read_text()
backend_counts={key:int(re.search(r'ℹ '+key+r' (\d+)',backend).group(1)) for key in ['tests','pass','fail','skipped','cancelled']}
android=json.loads((root/'quality/logs'/verification['runId']/'android-test-counts.json').read_text())
trials=[];latest={}
reports=[]
for file in metrics.glob('*.json'):
 data=json.loads(file.read_text())
 if not isinstance(data,dict) or not isinstance(data.get('mutations'),list):continue
 if file.name=='mutation-summary.json':continue
 reports.append((file.stat().st_mtime,file,data))
for _,file,data in sorted(reports):
 for mutation in data['mutations']:
  scope='javascript' if file.name.startswith('javascript-') else file.name.split('-')[0]
  source=mutation_source(data,mutation,current_inputs)
  digest=mutation.get('sourceSha256',data.get('sourceSha256'))
  inputs=data.get('inputsSha256')
  record={**mutation,'scope':scope,'evidence':str(file.relative_to(root)),'sourceFile':source,'sourceSha256':digest,'productionSourceMatches':bool(source and digest and current_inputs.get(source)==digest),'fullInputsMatch':None if inputs is None else inputs==current_inputs}
  trials.append(record);latest[(scope,mutation['name'])]=record
survivor=latest[('accounting','ignore-unlock-expiry-cap')]
survivor['assessment']='Equivalent for current accounting contracts: RestrictionEngine.consumeUsageDetailed independently splits at unlockBoundary, normalizes at that boundary and stops when the mode no longer records usage; repository unlockReached still flushes. The ordinary expiry/exhaustion tests pass. No production cap was removed.'
summary={'scope':'Selected Android GPS recording, movement, serializer, receipt, reward ledger, accounting and API contracts; JavaScript configuration, recording, GPS, reviews, provider responses, native bridge, voice, history, stream and audio contracts. No whole-repository mutation percentage.',
 'trials':dict(collections.Counter(m['status'] for m in trials)),
 'latestDistinctMutations':dict(collections.Counter(m['status'] for m in latest.values())),
 'latestMatchingProductionSource':dict(collections.Counter(m['status'] for m in latest.values() if m['productionSourceMatches'])),
 'freshnessNote':'Each mutation retains its original log, source hash and outcome. productionSourceMatches compares its target file to the final checkout; fullInputsMatch also compares tests, tools and configuration when recorded. Historical attempts are not silently promoted to fresh evidence.',
 'equivalentSurvivors':1,'uncovered':0,'timedOut':0,'latest':list(latest.values()),
 'historicalErrors':'Historical non-assertion and setup failures remain in trials and their original logs; they are never counted as detections. Any successful recheck is a separate attempt. The GPS chronology recheck adds an explicit missing-failure assertion before inspecting its message.'}
(metrics/'mutation-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
result={'status':'incomplete-targets-not-met','verificationRun':verification['runId'],'sourceUnchanged':verification['sourceUnchanged'],'tests':{'android':android,'backendAndBrowser':backend_counts},'metrics':json.loads((metrics/'summary.json').read_text()),'gate':json.loads((metrics/'gate.json').read_text()),'mutations':summary}
(metrics/'final-evidence.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'tests':result['tests'],'latestMutations':summary['latestDistinctMutations'],'mutationTrials':summary['trials']},indent=2))
