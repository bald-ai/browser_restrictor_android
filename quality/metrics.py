"""Merge compiler-PSI spans and JaCoCo source-line counters, retaining unmapped rows."""

import os as _session_os, sys as _session_sys, subprocess as _session_subprocess
from pathlib import Path as _SessionPath
if not _session_os.environ.get('VERIFICATION_SESSION_ROOT'):
    raise SystemExit(_session_subprocess.call(['python3', str(_SessionPath(__file__).resolve().parents[1] / 'scripts/verification-session.py'), 'metrics', *_session_sys.argv[1:]]))
import pathlib,json,xml.etree.ElementTree as ET,collections
from coverage_join import unique_method
from probe_coverage import read_probes, exact_probe_coverage, source_branch_coverage
from function_metrics import read_kotlin_function, summarize_functions
root=pathlib.Path(__file__).resolve().parent.parent
xml=root/'app/build/reports/jacoco/unitCoverage/unitCoverage.xml'
r=ET.parse(xml).getroot();sources={}
for package in r.findall('package'):
 for source in package.findall('sourcefile'):
  sources['app/src/main/java/'+package.get('name')+'/'+source.get('name')]={int(line.get('nr')):{k:int(line.get(k)) for k in ['mi','ci','mb','cb']} for line in source.findall('line')}
classes={}
for line in (root/'quality/metrics/kotlin-classes.tsv').read_text().splitlines():
 name,parent,sha=line.split('\t');classes[name]={'superName':parent,'classSha256':sha}
methods=[]
for package in r.findall('package'):
 for cls in package.findall('class'):
  file='app/src/main/java/'+package.get('name')+'/'+cls.get('sourcefilename','')
  for method in cls.findall('method'):
   if method.get('line') is None:continue
   methods.append(dict(file=file,owner=cls.get('name'),**classes.get(cls.get('name'),{}),name=method.get('name'),descriptor=method.get('desc'),line=int(method.get('line')),counters={c.get('type'):{k:int(c.get(k)) for k in ['missed','covered']} for c in method.findall('counter')}))
probes, probe_events = read_probes(root/'quality/metrics/kotlin-probes.tsv', root/'quality/metrics/kotlin-probe-events.txt', root)
rows=[]
for line in (root/'quality/metrics/kotlin-functions.tsv').read_text().splitlines():
 rows.append(read_kotlin_function(line))
function_probes=[]
for row in rows:
 if row['classification']=='abstract-declaration':
  row.update(coverageBasis='not-applicable-abstract-declaration', coverageLines=[],
             covered=None, total=None, coverageFraction=None, crap=None)
  continue
 observed=exact_probe_coverage(row,probes,probe_events)
 function_probes.append(observed)
 nested=[r for r in rows if r['file']==row['file'] and row['startOffset']<r['startOffset'] and r['endOffset']<=row['endOffset']]
 siblings=[r for r in rows if r['file']==row['file'] and r is not row and not (r['startOffset']<=row['startOffset'] and row['endOffset']<=r['endOffset']) and r not in nested]
 lines={n:v for n,v in sources.get(row['file'],{}).items() if row['startLine']<=n<=row['endLine'] and not any(c['startLine']<=n<=c['endLine'] for c in nested+siblings)}
 # Inline lambda boundary lines can execute in the enclosing expression without
 # invoking the lambda. JaCoCo cannot distinguish those hits; leave them unmapped.
 if row['name']=='KtFunctionLiteral':
  lines.pop(row['startLine'],None);lines.pop(row['endLine'],None)
 branches=sum(v['mb']+v['cb'] for v in lines.values())
 if branches:total=branches;covered=sum(v['cb'] for v in lines.values());basis='jacoco-source-branch'
 else:total=len(lines);covered=sum(v['ci']>0 for v in lines.values());basis='jacoco-source-line'
 if not total:
  matched=unique_method(row,rows,methods)
  if matched:
   counter=matched['counters'].get('BRANCH',{})
   kind='branch'
   if not sum(counter.values()):counter=matched['counters']['LINE'];kind='line'
   total=sum(counter.values());covered=counter.get('covered',0);basis='jacoco-unique-method-'+kind
   row['coverageMethod']={k:matched[k] for k in ['owner','name','descriptor','line','superName','classSha256'] if k in matched}
 # Source conditions have exact callable identity and precede JVM/Compose lowering.
 # Prefer them to bytecode branches even when JaCoCo has a broader line counter.
 if observed and (observed['coverageBasis']=='kotlin-ir-source-branch' or not total):
  row.update(observed);total=observed['total'];covered=observed['covered'];basis=observed['coverageBasis']
 fraction=covered/total if total else None
 row.update(coverageBasis=basis if total else 'unmapped',coverageLines=list(lines),covered=covered,total=total,coverageFraction=fraction,crap=None if fraction is None else row['complexity']**2*(1-fraction)**3+row['complexity'])
(root/'quality/metrics/android-functions.json').write_text(json.dumps(rows,indent=2)+'\n')
js=json.loads((root/'quality/metrics/javascript-functions.json').read_text());cov=json.loads((root/'quality/metrics/istanbul/summary.json').read_text())
counts={c.get('type'):{k:int(c.get(k)) for k in ['missed','covered']} for c in r.findall('counter')}
cov['android']={k:{**counts[t], 'total':sum(counts[t].values()),'pct':100*counts[t]['covered']/sum(counts[t].values())} for k,t in [('lines','LINE'),('branches','BRANCH')]}
cov['android']['bytecodeBranches']=cov['android']['branches']
cov['android']['branches']=source_branch_coverage(probes,probe_events,function_probes)
summary={}
for component in ['android','server','browser']:
 functions=[f for f in rows+js if f['component']==component]
 summary[component]=dict(coverage=cov[component],**summarize_functions(functions))
summary['nonExecutableDeclarations']=[{k:f[k] for k in ['file','name','startLine','classification']} for f in rows if f['classification']=='abstract-declaration']
summary['exclusions']=['dependencies','tests','BuildConfig and R generated classes','non-executable resources and documentation'];summary['notes']=['All backend root .mjs and public .js included.','Android lines retain all compiled main classes except R/BuildConfig. Handwritten branches use exact Kotlin IR source conditions; raw JaCoCo bytecode branch totals remain separately reported.','Nested spans, sibling-shared lines and inline lambda boundary lines are excluded from per-function joins because JaCoCo cannot attribute their hits uniquely.','Unmapped leaf functions may use a unique kind/name/source-matched JVM method; ambiguous, inlined or counterless functions remain null, never assumed covered.','Kotlin boundaries use Kotlin compiler PSI 2.3.20; JavaScript uses Acorn AST.']
summary['androidSourceFilesWithoutCounters']=sorted(str(p.relative_to(root)) for p in (root/'app/src/main/java').rglob('*.kt') if str(p.relative_to(root)) not in sources)
(root/'quality/metrics/summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({k:{a:b for a,b in v.items() if a!='coverage'} for k,v in summary.items() if k in ['android','server','browser']},indent=2))
