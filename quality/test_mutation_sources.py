import unittest
from mutation_sources import mutation_source, ANDROID

class MutationSourceTest(unittest.TestCase):
    def test_legacy_class_scopes_resolve_to_real_unique_files(self):
        paths = {ANDROID+'v1/GpsDistanceTracker.kt':'gps', ANDROID+'data/RestrictorRepository.kt':'repo'}
        self.assertEqual(mutation_source({'scope':'GpsDistanceTracker only; focused rule tests'},{},paths), ANDROID+'v1/GpsDistanceTracker.kt')
        self.assertEqual(mutation_source({'scope':'RestrictorRepository accounting; JVM suite'},{},paths), ANDROID+'data/RestrictorRepository.kt')
    def test_ambiguous_or_missing_class_scopes_remain_unresolved(self):
        paths = {ANDROID+'a/Example.kt':'a',ANDROID+'b/Example.kt':'b'}
        self.assertIsNone(mutation_source({'scope':'Example historical tests'},{},paths))
        self.assertIsNone(mutation_source({'scope':'Missing class tests'},{},paths))
    def test_explicit_targets_remain_exact_even_after_source_disappears(self):
        self.assertEqual(mutation_source({'sourceFile':'old/file.kt'},{},{}),'old/file.kt')
        self.assertEqual(mutation_source({}, {'file':'public/app.js'},{}),'backend-v1/public/app.js')
        self.assertEqual(mutation_source({'scope':'data/AppStateSerializer.kt with *SavedStateValidationTest'},{},{}),ANDROID+'data/AppStateSerializer.kt')
