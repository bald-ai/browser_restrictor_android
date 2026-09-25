"""Execute the compiler probes, including inline, suspension and shared-line fixtures."""
import pathlib,subprocess,tempfile,unittest
from probe_coverage import read_probes
ROOT=pathlib.Path(__file__).resolve().parent.parent

class ProbeCompilerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory=tempfile.TemporaryDirectory(prefix='kotlin-probes-')
        cls.addClassCleanup(cls.directory.cleanup)
        cls.work=pathlib.Path(cls.directory.name)
        build=ROOT/'quality/probes/build'
        cp=(build/'compiler-classpath.txt').read_text()
        compiler=['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp]
        sources=[str(ROOT/'quality/probes/fixtures/ProbeFixture.kt'),str(ROOT/'quality/probes/runtime/QualityProbeRuntime.kt')]
        cls.results=[]
        for instrumented in [False,True]:
            target=cls.work/str(instrumented)
            options=['-Xplugin='+str(build/'quality-probes.jar'),'-P','plugin:friction.quality.probes:manifest='+str(cls.work/'manifest.tsv')] if instrumented else []
            subprocess.run(compiler+options+['-d',str(target)]+sources,check=True,capture_output=True,text=True)
            result=subprocess.run(['java','-Dquality.probe.events='+str(cls.work/'events.txt'),'-cp',str(target)+':'+cp,'fixture.ProbeFixtureKt'],capture_output=True,text=True,check=True)
            cls.results.append(result.stdout)
        cls.probes,cls.events=read_probes(cls.work/'manifest.tsv',cls.work/'events.txt',ROOT)

    def conditions(self,name):
        return [p for p in self.probes if p['name']==name and p['kind']=='condition']

    def test_instrumentation_preserves_results_exceptions_and_nonlocal_returns(self):
        self.assertEqual(['semantic checks passed\n']*2,self.results)

    def test_shared_line_lambdas_remain_distinct(self):
        source=(ROOT/'quality/probes/fixtures/ProbeFixture.kt').read_text()
        called=source.index('{ branch(2) }');unused=source.index('{ branch(-3) }')
        entries={p['start']:p['id'] for p in self.probes if p['name']=='<anonymous>' and p['kind']=='entry'}
        self.assertIn(entries[called],self.events)
        self.assertNotIn(entries[unused],self.events)

    def test_conditions_loops_nullable_paths_and_resumed_coroutines_record_both_edges(self):
        for name in ['choose','branch','maybe','loop','resumed']:
            conditions=self.conditions(name)
            self.assertTrue(conditions,name)
            for probe in conditions:
                self.assertIn(probe['id']+':true',self.events)
                self.assertIn(probe['id']+':false',self.events)

    def test_constant_loops_do_not_invent_impossible_outcomes(self):
        conditions=self.conditions('constantLoops')
        self.assertEqual(1,len(conditions)) # Only the data-dependent break decision.
        self.assertIn(conditions[0]['id']+':true',self.events)
        self.assertIn(conditions[0]['id']+':false',self.events)
