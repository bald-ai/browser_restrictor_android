import hashlib,pathlib
ROOT=pathlib.Path(__file__).resolve().parent.parent
def source_manifest():
 files={p for p in (ROOT/'app/src').rglob('*') if p.is_file()}
 files.update(p for p in (ROOT/'quality/probes').rglob('*.kt') if 'build' not in p.relative_to(ROOT/'quality/probes').parts)
 files.update((ROOT/'scripts').glob('*.py'));files.update((ROOT/'quality').glob('*.py'));files.update((ROOT/'quality').glob('*.mjs'));files.update((ROOT/'quality/parser').glob('*.java'))
 for extension in ['*.mjs','*.js']:files.update((ROOT/'backend-v1').glob(extension))
 for directory in ['backend-v1/public','backend-v1/test','backend-v1/docs']:
  files.update(p for p in (ROOT/directory).rglob('*') if p.is_file())
 for file in ['package.json','AGENTS.md','app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle/wrapper/gradle-wrapper.properties','backend-v1/package.json','backend-v1/package-lock.json','AGENT_CAPABILITIES.md','backend-v1/docs/app-guide.md','backend-v1/docs/app-overview.md']:
  files.add(ROOT/file)
 return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}


MEASUREMENT_FILES = [
 'app/build/reports/jacoco/unitCoverage/unitCoverage.xml',
 'quality/metrics/istanbul/coverage-final.json', 'quality/metrics/istanbul/summary.json',
 'quality/metrics/kotlin-functions.tsv', 'quality/metrics/android-functions.json',
 'quality/metrics/kotlin-classes.tsv',
 'quality/metrics/kotlin-probes.tsv', 'quality/metrics/kotlin-probe-events.txt',
 'quality/metrics/javascript-functions.json', 'quality/metrics/summary.json',
]
def measurement_manifest():
 return {file:hashlib.sha256((ROOT/file).read_bytes()).hexdigest() for file in MEASUREMENT_FILES}
