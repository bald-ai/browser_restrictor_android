import pathlib
import subprocess
import tempfile
import unittest

from function_metrics import read_kotlin_function, summarize_functions

ROOT = pathlib.Path(__file__).resolve().parent.parent


class FunctionClassificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix='function-classification-')
        cls.addClassCleanup(cls.directory.cleanup)
        work = pathlib.Path(cls.directory.name)
        cp = (ROOT / 'quality/probes/build/compiler-classpath.txt').read_text()
        subprocess.run(['javac', '-cp', cp, '-d', str(work),
                        str(ROOT / 'quality/parser/KotlinFunctions.java')], check=True, capture_output=True)
        fixture = work / 'sources'
        fixture.mkdir()
        (fixture / 'Example.kt').write_text('''
interface Contract {
    fun promised(): Int
    fun defaultValue(): Int = 1
    fun defaultBody(): Int { return 2 }
}
abstract class Base {
    abstract fun required(): Int
    fun empty() {}
}
class Implementation(val value: Int) : Contract {
    override fun promised(): Int = value
    var counter: Int = 0
        private set
    fun callback(): () -> Unit = {}
}
external fun nativeValue(): Int
''')
        result = subprocess.run(['java', '-cp', str(work) + ':' + cp,
                                 'KotlinFunctions', str(fixture)], check=True, capture_output=True, text=True)
        cls.rows = [read_kotlin_function(line) for line in result.stdout.splitlines()]

    def test_only_bodyless_abstract_contracts_are_non_executable(self):
        declarations = [r for r in self.rows if r['classification'] == 'abstract-declaration']
        self.assertEqual(['promised', 'required'], [r['name'] for r in declarations])
        self.assertTrue(all(r['complexity'] is None for r in declarations))

    def test_default_empty_native_constructor_accessor_and_lambda_bodies_remain_in_scope(self):
        executable = [r for r in self.rows if r['classification'] == 'executable']
        names = {r['name'] for r in executable}
        self.assertTrue({'defaultValue', 'defaultBody', 'empty', 'promised', 'nativeValue',
                         'KtPrimaryConstructor', 'set:counter', 'KtFunctionLiteral'} <= names, names)
        self.assertTrue(all(r['complexity'] >= 1 for r in executable))


class FunctionSummaryTest(unittest.TestCase):
    def test_declarations_are_visible_but_missing_executable_coverage_stays_unresolved(self):
        summary = summarize_functions([
            dict(classification='abstract-declaration', complexity=None, crap=None),
            dict(classification='executable', complexity=1, crap=None),
            dict(classification='executable', complexity=5, crap=10.5),
        ])
        self.assertEqual(3, summary['functions'])
        self.assertEqual(2, summary['executableFunctions'])
        self.assertEqual(1, summary['nonExecutableDeclarations'])
        self.assertEqual(1, summary['unmappedFunctions'])
        self.assertEqual(1, summary['crapAboveLimit'])
        self.assertEqual(10.5, summary['maxCrap'])

    def test_unclassified_javascript_functions_still_fail_when_unmapped(self):
        self.assertEqual(1, summarize_functions([dict(complexity=1, crap=None)])['unmappedFunctions'])

    def test_unknown_parser_classification_cannot_silently_drop_a_function(self):
        with self.assertRaises(ValueError):
            read_kotlin_function('Example.kt\tf\t0\t1\t1\t1\t1\tunknown')
