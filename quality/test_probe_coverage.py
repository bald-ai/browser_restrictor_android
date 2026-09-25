import unittest
from probe_coverage import exact_probe_coverage, source_branch_coverage

class ProbeCoverageTest(unittest.TestCase):
    def setUp(self):
        self.row = dict(file='test.kt', name='KtFunctionLiteral', startOffset=10, endOffset=30)
        self.entry = dict(id='test.kt:10:30:<anonymous>:entry', file='test.kt', name='<anonymous>',
                          start=10, end=30, bodyStart=12, bodyEnd=28, kind='entry')

    def test_same_line_sibling_cannot_supply_an_invocation(self):
        sibling = {**self.entry, 'start': 40, 'end': 60, 'id': 'sibling:entry'}
        result = exact_probe_coverage(self.row, [self.entry, sibling], {'sibling:entry'})
        self.assertEqual((0, 1), (result['covered'], result['total']))
        self.assertIsNone(exact_probe_coverage(self.row, [sibling], {'sibling:entry'}))

    def test_condition_outcomes_are_separate_and_generated_scaffolding_is_identified(self):
        condition = {**self.entry, 'id': self.entry['id'].replace(':entry', ':condition:0'), 'kind': 'condition'}
        generated = {**condition, 'id': condition['id'] + '-generated', 'bodyStart': -1, 'bodyEnd': -1}
        result = exact_probe_coverage(self.row, [self.entry, condition, generated], {condition['id'] + ':true'})
        self.assertEqual('kotlin-ir-source-branch', result['coverageBasis'])
        self.assertEqual((1, 2), (result['covered'], result['total']))

    def test_ambiguous_or_wrong_kind_candidates_remain_unresolved(self):
        self.assertIsNone(exact_probe_coverage(self.row, [self.entry, self.entry], set()))
        self.assertIsNone(exact_probe_coverage(self.row, [{**self.entry, 'name': 'other'}], set()))

    def test_function_modifiers_can_precede_ir_start_but_end_must_match(self):
        row = {**self.row, 'name': 'work', 'startOffset': 0}
        self.assertIsNotNone(exact_probe_coverage(row, [{**self.entry, 'name': 'work'}], set()))
        self.assertIsNone(exact_probe_coverage(row, [{**self.entry, 'name': 'work', 'end': 29}], set()))

    def test_aggregate_keeps_uncovered_source_edges_and_audits_unowned_conditions(self):
        branch = {**self.entry, 'id': self.entry['id'].replace(':entry', ':condition:0'), 'kind': 'condition'}
        second = {**branch, 'id': 'unowned', 'start': 40, 'end': 60, 'bodyStart': 45, 'bodyEnd': 50}
        generated = {**branch, 'id': 'generated', 'bodyStart': -1, 'bodyEnd': -1}
        events = {branch['id'] + ':true', 'generated:true'}
        observed = exact_probe_coverage(self.row, [self.entry, branch], events)
        result = source_branch_coverage([branch, second, generated], events, [observed])
        self.assertEqual((1, 4, 25), (result['covered'], result['total'], result['pct']))
        self.assertEqual(['unowned:false', 'unowned:true'], result['unownedProbeIds'])

    def test_aggregate_never_substitutes_entry_or_line_hits_for_branch_outcomes(self):
        branch = {**self.entry, 'id': self.entry['id'].replace(':entry', ':condition:0'), 'kind': 'condition'}
        result = source_branch_coverage([self.entry, branch], {self.entry['id']}, [])
        self.assertEqual(0, result['covered'])
        self.assertEqual(2, result['total'])
        with self.assertRaisesRegex(ValueError, 'No handwritten branch'):
            source_branch_coverage([self.entry], {self.entry['id']}, [])
