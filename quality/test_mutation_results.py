import unittest
from xml.etree.ElementTree import Element

from mutation_results import is_assertion_failure


class MutationResultTest(unittest.TestCase):
    def test_junit_comparisons_are_assertions(self):
        for kind in ['java.lang.AssertionError', 'org.junit.ComparisonFailure',
                     'junit.framework.ComparisonFailure', 'org.opentest4j.AssertionFailedError']:
            with self.subTest(kind=kind):
                self.assertTrue(is_assertion_failure(Element('failure', type=kind)))

    def test_crashes_and_missing_types_are_not_assertion_detections(self):
        for kind in ['', 'java.lang.IllegalStateException', 'org.json.JSONException',
                     'java.lang.NullPointerException']:
            with self.subTest(kind=kind):
                self.assertFalse(is_assertion_failure(Element('failure', type=kind)))
