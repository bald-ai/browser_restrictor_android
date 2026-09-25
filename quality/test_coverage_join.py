import unittest
from coverage_join import unique_method

def function(name='KtFunctionLiteral', start=10, end=10, offset=100):
    return dict(file='Example.kt', name=name, startLine=start, endLine=end,
                startOffset=offset, endOffset=offset+10)

def method(name='render$lambda$1', line=10, covered=0):
    return dict(file='Example.kt', name=name, line=line, descriptor='()V',
                counters={'LINE': {'missed': 1-covered, 'covered': covered},
                          'BRANCH': {'missed': 2, 'covered': 0}})

class CoverageJoinTest(unittest.TestCase):
    def test_unique_unexecuted_lambda_retains_zero_real_coverage(self):
        row=function();candidate=method()
        self.assertIs(unique_method(row,[row],[candidate]),candidate)
        self.assertEqual(candidate['counters']['BRANCH']['covered'],0)
    def test_shared_line_lambdas_are_not_assumed_to_have_the_same_coverage(self):
        rows=[function(offset=100),function(offset=120)]
        self.assertIsNone(unique_method(rows[0],rows,[method(covered=1)]))
    def test_multiple_compiled_methods_are_ambiguous(self):
        row=function()
        self.assertIsNone(unique_method(row,[row],[method(),method(name='render$lambda$2')]))
    def test_inlined_lambda_cannot_borrow_its_enclosing_method(self):
        row=function()
        self.assertIsNone(unique_method(row,[row],[method(name='render',covered=1)]))
    def test_wrong_file_or_source_line_cannot_match(self):
        row=function();candidate=method();candidate['file']='Other.kt'
        self.assertIsNone(unique_method(row,[row],[candidate,method(line=11)]))
    def test_parent_with_child_cannot_borrow_inlined_child_coverage(self):
        parent=function('render',10,20);parent['endOffset']=200
        child=function(start=11,end=11,offset=120)
        self.assertIsNone(unique_method(parent,[parent,child],[method('render',10,1)]))
    def test_named_method_requires_an_exact_or_compiler_nested_name(self):
        row=function('invalid')
        self.assertIs(unique_method(row,[row],[candidate:=method('validate$invalid')]),candidate)
        self.assertIsNone(unique_method(row,[row],[method('invalidate')]))
    def test_generated_continuation_is_not_a_source_lambda(self):
        row=function()
        self.assertIsNone(unique_method(row,[row],[method('invokeSuspend',covered=1)]))

    def test_no_source_counters_is_not_evidence_of_coverage(self):
        row=function();candidate=method();candidate['counters']['LINE']={'missed':0,'covered':0}
        self.assertIsNone(unique_method(row,[row],[candidate]))

if __name__ == '__main__': unittest.main()

class CompiledKindJoinTest(unittest.TestCase):
    def test_suspend_lambda_requires_bytecode_superclass(self):
        row=function()
        candidate=method('invokeSuspend',covered=1)
        self.assertIsNone(unique_method(row,[row],[candidate]))
        candidate['superName']='kotlin/coroutines/jvm/internal/ContinuationImpl'
        self.assertIsNone(unique_method(row,[row],[candidate]))
        candidate['superName']='kotlin/coroutines/jvm/internal/SuspendLambda'
        self.assertIs(unique_method(row,[row],[candidate]),candidate)
    def test_class_evidence_does_not_resolve_two_lambdas_on_one_line(self):
        rows=[function(offset=100),function(offset=120)]
        candidate=method('invokeSuspend');candidate['superName']='kotlin/coroutines/jvm/internal/SuspendLambda'
        self.assertIsNone(unique_method(rows[0],rows,[candidate]))
    def test_named_property_accessor_matches_only_its_property(self):
        row=function('get:enabled');candidate=method('getEnabled')
        self.assertIs(unique_method(row,[row],[candidate]),candidate)
        self.assertIsNone(unique_method(row,[row],[method('getOther')]))
        row=function('get:isEnabled');candidate=method('isEnabled')
        self.assertIs(unique_method(row,[row],[candidate]),candidate)
    def test_regular_lambda_requires_a_lambda_class_not_an_arbitrary_invoke(self):
        row=function();candidate=method('invoke')
        self.assertIsNone(unique_method(row,[row],[candidate]))
        candidate['superName']='kotlin/jvm/internal/Lambda'
        self.assertIs(unique_method(row,[row],[candidate]),candidate)
