"""Changes in fixtures or provider artifacts must invalidate saved evidence."""
import pathlib,tempfile,unittest
from unittest.mock import patch
import source_manifest

class SourceManifestTest(unittest.TestCase):
 def test_nested_fixture_edits_and_additions_change_source_manifest(self):
  # Use real inventory paths in a disposable root; never edit project inputs.
  with tempfile.TemporaryDirectory() as temporary:
   root=pathlib.Path(temporary)
   for name in source_manifest.source_manifest():
    target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text('fixture')
   with patch.object(source_manifest,'ROOT',root):
    before=source_manifest.source_manifest()
    fixture=root/'backend-v1/test/fixtures/gps-parity.json'
    self.assertIn(str(fixture.relative_to(root)),before)
    fixture.write_text('changed distance reference')
    self.assertNotEqual(before,source_manifest.source_manifest())
    added=root/'app/src/test/resources/new-state.bin';added.parent.mkdir(parents=True,exist_ok=True);added.write_bytes(b'new')
    self.assertIn('app/src/test/resources/new-state.bin',source_manifest.source_manifest())
 def test_raw_measurement_tampering_changes_manifest_and_missing_counter_file_fails(self):
  with tempfile.TemporaryDirectory() as temporary:
   root=pathlib.Path(temporary)
   for name in source_manifest.MEASUREMENT_FILES:
    target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text('counter')
   with patch.object(source_manifest,'ROOT',root):
    before=source_manifest.measurement_manifest()
    target=root/source_manifest.MEASUREMENT_FILES[0];target.write_text('different counter')
    self.assertNotEqual(before,source_manifest.measurement_manifest())
    target.unlink()
    with self.assertRaises(FileNotFoundError):source_manifest.measurement_manifest()
