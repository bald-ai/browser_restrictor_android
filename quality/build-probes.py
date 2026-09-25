"""Build the optional Kotlin IR measurement plugin with the project's pinned compiler."""
import os,pathlib,re,subprocess
ROOT=pathlib.Path(__file__).resolve().parent.parent
cache=pathlib.Path(os.environ.get('GRADLE_USER_HOME',pathlib.Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
version=re.search(r'id\("org.jetbrains.kotlin.android"\) version "([^"]+)"',(ROOT/'build.gradle.kts').read_text())[1]
patterns=[f'org.jetbrains.kotlin/kotlin-compiler-embeddable/{version}/**/*.jar',f'org.jetbrains.kotlin/kotlin-stdlib/{version}/**/*.jar','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/**/*.jar','org.jetbrains/annotations/**/*.jar','org.jetbrains.kotlin/kotlin-script-runtime/**/*.jar','org.jetbrains.kotlin/kotlin-reflect/**/*.jar']
cp=os.pathsep.join(str(p) for pattern in patterns for p in cache.glob(pattern))
build=ROOT/'quality/probes/build';build.mkdir(exist_ok=True)
classes=build/'classes';classes.mkdir(exist_ok=True)
subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(classes),str(ROOT/'quality/probes/QualityProbePlugin.kt')],check=True)
services=classes/'META-INF/services';services.mkdir(parents=True,exist_ok=True)
for interface,implementation in [('CompilerPluginRegistrar','ProbeRegistrar'),('CommandLineProcessor','ProbeOptions')]:
 (services/f'org.jetbrains.kotlin.compiler.plugin.{interface}').write_text(f'friction.quality.compiler.{implementation}\n')
subprocess.run(['jar','cf',str(build/'quality-probes.jar'),'-C',str(classes),'.'],check=True)
(build/'compiler-classpath.txt').write_text(cp)
