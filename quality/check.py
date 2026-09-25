"""Fail on unmet targets; never waive unmapped executable functions."""

import os as _session_os, sys as _session_sys, subprocess as _session_subprocess
from pathlib import Path as _SessionPath
if not _session_os.environ.get('VERIFICATION_SESSION_ROOT'):
    raise SystemExit(_session_subprocess.call(['python3', str(_SessionPath(__file__).resolve().parents[1] / 'scripts/verification-session.py'), 'metrics', *_session_sys.argv[1:]]))
import json,pathlib,sys
from source_manifest import source_manifest, measurement_manifest
from function_metrics import CRAP_LIMIT
root=pathlib.Path(__file__).resolve().parent.parent
metrics=json.loads((root/'quality/metrics/summary.json').read_text())
failures=[]
manifest=root/'quality/metrics/source-manifest.json'
if not manifest.exists() or json.loads(manifest.read_text())!=source_manifest():failures.append('Source changed or verification manifest missing; rerun quality/run.py.')
try:
 saved=json.loads((root/'quality/metrics/measurement-manifest.json').read_text())
 if saved!=measurement_manifest():failures.append('Measurement artifacts changed; rerun quality/run.py.')
except (OSError,ValueError):failures.append('Measurement artifact manifest missing or unreadable; rerun quality/run.py.')
for component in ['android','server','browser']:
 data=metrics[component]
 for metric,target in [('lines',90),('branches',85)]:
  actual=data['coverage'][metric]['pct']
  if not isinstance(actual,(float,int)) or actual<target:failures.append(f'{component} {metric}: {actual} < {target}')
 if data['coverage']['branches'].get('unownedProbeIds'):failures.append(f'{component}: handwritten branch ownership is unresolved')
 if data['crapAboveLimit']:failures.append(f'{component}: {data["crapAboveLimit"]} functions have CRAP >{CRAP_LIMIT}')
 if data['unmappedFunctions']:failures.append(f'{component}: {data["unmappedFunctions"]} executable functions have unverified coverage')
result={'passed':not failures,'failures':failures,'targets':{'lines':90,'branches':85,'crap':CRAP_LIMIT},'exceptions':[]}
(root/'quality/metrics/gate.json').write_text(json.dumps(result,indent=2)+'\n')
print('\n'.join(failures) if failures else 'All whole-component targets passed.')
sys.exit(bool(failures))
