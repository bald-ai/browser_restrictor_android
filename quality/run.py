"""Fresh ordinary tests, complete instrumentation, function metrics and strict gate.
Does not launch any device, emulator, activity or browser. HTTP tests use loopback;
Robolectric tests use in-memory Android fixtures. Mutations are a separate command.
"""

import os as _session_os, sys as _session_sys, subprocess as _session_subprocess
from pathlib import Path as _SessionPath
if not _session_os.environ.get('VERIFICATION_SESSION_ROOT'):
    raise SystemExit(_session_subprocess.call(['python3', str(_SessionPath(__file__).resolve().parents[1] / 'scripts/verification-session.py'), 'metrics', *_session_sys.argv[1:]]))
import hashlib,json,pathlib,subprocess,time,sys,os,shutil,xml.etree.ElementTree as ET
from source_manifest import source_manifest as manifest, measurement_manifest
root=pathlib.Path(__file__).resolve().parent.parent
run_id='verification-'+str(time.time_ns());logs=root/'quality/logs'/run_id;logs.mkdir(parents=True)
before=manifest();results=[]
environment={**os.environ,'OPENAI_API_KEY':'','OPENROUTER_API_KEY':''};environment.pop('PUBLIC_BASE_URL',None)
commands = [
 ('probe-compiler', ['python3', 'quality/build-probes.py']),
 ('measurement-tests', ['python3', '-m', 'unittest', 'discover', '-s', 'quality', '-p', 'test_*.py']),
 ('backend-ordinary', ['npm', '--prefix', 'backend-v1', 'test']),
 ('android-ordinary', ['./gradlew', ':app:testDebugUnitTest', '--rerun', ':app:unitCoverage']),
 # Preserve ordinary class metadata before the separate instrumented compilation.
 ('kotlin-functions', ['python3', 'quality/kotlin-functions.py']),
 ('android-probes', ['./gradlew', ':app:testDebugUnitTest', '--rerun', '-PqualityProbes=true', '-Pkotlin.incremental=false']),
 ('javascript-coverage', ['node', 'quality/javascript-coverage.mjs']),
 ('javascript-functions', ['node', 'quality/javascript-functions.mjs']),
 ('metrics', ['python3', 'quality/metrics.py']),
 ('gate', ['python3', 'quality/check.py']),
]
for name,command in commands:
 if command[0]=='./gradlew' and os.environ.get('QUALITY_JAVA_HOME'):
  command.insert(1,'-Dorg.gradle.java.home='+os.environ['QUALITY_JAVA_HOME'])
 if name=='gate':
  if before!=manifest():raise RuntimeError('Source changed before coverage join completed.')
  (root/'quality/metrics/source-manifest.json').write_text(json.dumps(before,indent=2)+'\n')
  (root/'quality/metrics/measurement-manifest.json').write_text(json.dumps(measurement_manifest(),indent=2)+'\n')
 with (logs/(name+'.log')).open('w')as output:run=subprocess.run(command,cwd=root,stdout=output,stderr=subprocess.STDOUT,env=environment)
 (logs/(name+'.exit')).write_text(str(run.returncode)+'\n');results.append({'name':name,'command':command,'exit':run.returncode,'log':str((logs/(name+'.log')).relative_to(root))})
 print(name,run.returncode,flush=True)
 if name in ('android-ordinary','android-probes') and run.returncode == 0:
  junit=root/'app/build/test-results/testDebugUnitTest'
  shutil.copytree(junit,logs/('junit' if name=='android-ordinary' else 'junit-probes'),ignore=shutil.ignore_patterns('binary'))
  suites=[ET.parse(file).getroot() for file in junit.glob('TEST-*.xml')]
  counts={key:sum(int(s.get(key,0)) for s in suites) for key in ['tests','failures','errors','skipped']}
  (logs/('android-test-counts.json' if name=='android-ordinary' else 'android-probe-test-counts.json')).write_text(json.dumps(counts,indent=2)+'\n')
  if name=='android-ordinary':
   shutil.copyfile(root/'app/build/reports/jacoco/unitCoverage/unitCoverage.xml',root/'quality/metrics/android-jacoco.xml')
 if run.returncode and name!='gate':break
if any(step['name']=='metrics' and step['exit']==0 for step in results):
 for file in measurement_manifest():
  destination=logs/'measurement'/file
  destination.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(root/file,destination)
unchanged=before==manifest()
report={'runId':run_id,'sourceUnchanged':unchanged,'sourceSha256':before,'steps':results}
(root/'quality/metrics/verification.json').write_text(json.dumps(report,indent=2)+'\n')
(logs/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
if not unchanged:raise RuntimeError('Source changed during verification; discard joined metrics and rerun.')
if any(step['name']=='metrics' and step['exit']==0 for step in results):
 summary=json.loads((root/'quality/metrics/summary.json').read_text())
 print('Component | Line coverage | Branch coverage | Maximum CRAP | Above limit | Unmapped | Non-executable declarations',flush=True)
 for component in ['android','server','browser']:
  item=summary[component]
  print(f"{component} | {item['coverage']['lines']['pct']:.2f}% | {item['coverage']['branches']['pct']:.2f}% | {item['maxCrap']:.4f} | {item['crapAboveLimit']} | {item['unmappedFunctions']} | {item['nonExecutableDeclarations']}",flush=True)
 print('Non-executable inventory:', summary['nonExecutableDeclarations'],flush=True)
 print('Android raw bytecode branches:',summary['android']['coverage']['bytecodeBranches'],flush=True)
 print('Source unchanged:',unchanged,flush=True)
 for name in ['android-test-counts.json','android-probe-test-counts.json']:
  print(name,json.loads((logs/name).read_text()),flush=True)
 print('Strict gate:',json.loads((root/'quality/metrics/gate.json').read_text()),flush=True)

sys.exit(0 if all(r['exit']==0 for r in results) else 1)
