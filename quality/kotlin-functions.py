
import os as _session_os, sys as _session_sys, subprocess as _session_subprocess
from pathlib import Path as _SessionPath
if not _session_os.environ.get('VERIFICATION_SESSION_ROOT'):
    raise SystemExit('Internal measurement step. Use python3 scripts/verification-session.py metrics for a fresh, disposable run.')
import pathlib,subprocess,os,re
root=pathlib.Path(__file__).resolve().parent.parent
cache=pathlib.Path(os.environ.get('GRADLE_USER_HOME',pathlib.Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
version=re.search(r'id\("org.jetbrains.kotlin.android"\) version "([^"]+)"',(root/'build.gradle.kts').read_text()).group(1)
patterns=[f'org.jetbrains.kotlin/kotlin-compiler-embeddable/{version}/**/*.jar',f'org.jetbrains.kotlin/kotlin-stdlib/{version}/**/*.jar','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/**/*.jar','org.jetbrains/annotations/**/*.jar']
jars=[str(p) for pattern in patterns for p in cache.glob(pattern)]
cp=os.pathsep.join(jars)
build=root/'quality/parser/build';build.mkdir(exist_ok=True)
subprocess.run(['javac','-cp',cp,'-d',str(build),str(root/'quality/parser/KotlinFunctions.java')],check=True)
with (root/'quality/metrics/kotlin-functions.tsv').open('w') as out:
 subprocess.run(['java','-cp',str(build)+os.pathsep+cp,'KotlinFunctions','app/src/main/java'],cwd=root,stdout=out,check=True)
with (root/'quality/metrics/kotlin-classes.tsv').open('w') as out:
 subprocess.run(['java','-cp',str(build)+os.pathsep+cp,'KotlinFunctions','--classes','app/build/tmp/kotlin-classes/debug'],cwd=root,stdout=out,check=True)
