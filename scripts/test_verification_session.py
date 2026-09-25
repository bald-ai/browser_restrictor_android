"""Regression checks for disposable verification data; never run real metrics/mutations."""
import importlib.util
from pathlib import Path
import sys
import os
import subprocess
from unittest.mock import patch
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('verification_session', Path(__file__).with_name('verification-session.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class VerificationSessionTests(unittest.TestCase):
    def test_success_and_failure_leave_no_output_or_lock(self):
        for code in (0, 7):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                command = [sys.executable, '-c',
                           f"from pathlib import Path; Path('reports/result.json').write_text('temporary'); raise SystemExit({code})"]
                self.assertEqual(runner.run_session([command], ['reports'], root=root), code)
                self.assertFalse((root / 'reports').exists())
                self.assertFalse((root / '.verification-session').exists())

    def test_existing_output_is_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'reports').mkdir()
            (root / 'reports/keep').write_text('existing')
            with self.assertRaises(RuntimeError):
                runner.run_session([], ['reports'], root=root)
            self.assertEqual((root / 'reports/keep').read_text(), 'existing')
            self.assertFalse((root / '.verification-session').exists())

    def test_missing_command_still_cleans_up(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(FileNotFoundError):
                runner.run_session([[str(root / 'missing-program')]], ['reports'], root=root)
            self.assertFalse((root / 'reports').exists())
            self.assertFalse((root / '.verification-session').exists())

    def test_concurrent_session_is_rejected_without_removing_its_lock(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / '.verification-session').mkdir()
            with self.assertRaises(FileExistsError):
                runner.run_session([], ['reports'], root=root)
            self.assertTrue((root / '.verification-session').is_dir())
            self.assertFalse((root / 'reports').exists())


class AndroidTestCommandTests(unittest.TestCase):
    def test_explicit_jdk_overrides_global_gradle_selection_and_forwards_checks(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'scripts').mkdir()
            (root / 'scripts/test-android.sh').write_bytes(Path(__file__).with_name('test-android.sh').read_bytes())
            gradlew = root / 'gradlew'
            gradlew.write_text('#!/bin/sh\nprintf "%s\\n" "$JAVA_HOME" "$@"\nexit 7\n')
            gradlew.chmod(0o755)
            result = subprocess.run(['bash', str(root / 'scripts/test-android.sh'), 'lintDebug'],
                env={**os.environ, 'JAVA_HOME':'/old/jdk', 'ANDROID_TEST_JAVA_HOME':'/chosen jdk'},
                text=True, capture_output=True)
            self.assertEqual(result.returncode, 7)
            self.assertEqual(result.stdout.splitlines(), ['/chosen jdk', '-Dorg.gradle.java.home=/chosen jdk', 'testDebugUnitTest', '--console=plain', '--no-problems-report', 'lintDebug'])

    def test_manual_checks_pass_the_explicit_quality_jdk_to_nested_runners(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {'QUALITY_JAVA_HOME':'/chosen jdk', 'JAVA_HOME':'/old/jdk'}):
            root = Path(folder)
            command = [sys.executable, '-c', "import os; assert os.environ['JAVA_HOME'] == os.environ['QUALITY_JAVA_HOME'] == '/chosen jdk'"]
            self.assertEqual(runner.run_session([command], [], root=root), 0)


if __name__ == '__main__':
    unittest.main()
