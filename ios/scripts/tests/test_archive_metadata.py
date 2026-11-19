"""Portable checks for the metadata code used by the Release archive entry point.

Run its Python heredocs directly so these checks exercise the actual preparation
and archive/export gates without pretending Windows can build or sign an app.
"""
import copy
import json
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "archive-signed-app.sh"


def run_block(marker, *args):
    script = SCRIPT.read_text(encoding="utf-8")
    blocks = re.findall(r"<<'" + marker + r"'\n(.*?)\n" + marker + r"\n", script, re.S)
    if len(blocks) != 1:
        raise AssertionError("Expected one archive metadata heredoc: " + marker)
    return subprocess.run([sys.executable, "-c", blocks[0], *map(str, args)],
                          capture_output=True, text=True, check=False)


class ReleaseMetadataTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "build").mkdir()
        self.source = self.root / "build/Info.plist"
        self.output = self.root / "DeviceInfo.plist"
        self.settings_path = self.root / "settings.json"
        self.expected_path = self.root / "expected.json"
        self.report = self.root / "report.json"
        self.info = {
            "CFBundleShortVersionString": "$(MARKETING_VERSION)",
            "CFBundleVersion": "$(CURRENT_PROJECT_VERSION)",
            "UIBackgroundModes": ["location"],
            "NSLocationWhenInUseUsageDescription": "Record journeys you start.",
            "NSLocalNetworkUsageDescription": "Connect to your paired device service.",
            "UIApplicationSceneManifest": {"UIApplicationSupportsMultipleScenes": False},
            "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait"],
            "UILaunchScreen": {},
        }
        self.settings = {"PRODUCT_BUNDLE_IDENTIFIER": "com.mirage.iphone",
                         "GENERATE_INFOPLIST_FILE": "NO", "TARGETED_DEVICE_FAMILY": "1",
                         "MARKETING_VERSION": "0.2.0", "CURRENT_PROJECT_VERSION": "27",
                         "INFOPLIST_FILE": "build/Info.plist", "SRCROOT": str(self.root)}

    def prepare(self, info=None, settings=None):
        self.source.write_bytes(plistlib.dumps(self.info if info is None else info))
        self.settings_path.write_text(json.dumps([
            {"target": "MirageAppTests", "buildSettings": {"MARKETING_VERSION": "9.9.9"}},
            {"target": "Mirage", "buildSettings": self.settings if settings is None else settings}]))
        return run_block("PREPARE_METADATA_PY", self.settings_path, self.root,
                         self.output, self.expected_path, "27")

    def compiled(self):
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        info = plistlib.loads(self.output.read_bytes())
        info.update(json.loads(self.expected_path.read_text()))
        return info

    def verify(self, info, exported=False):
        if exported:
            artifact = self.root / "Mirage.ipa"
            with zipfile.ZipFile(artifact, "w") as archive:
                archive.writestr("Payload/Mirage.app/Info.plist", plistlib.dumps(info))
        else:
            artifact = self.root / "Mirage.app"
            artifact.mkdir(exist_ok=True)
            (artifact / "Info.plist").write_bytes(plistlib.dumps(info))
        return run_block("VERIFY_METADATA_PY", artifact, self.expected_path, self.report)

    def test_source_plist_preserved_and_release_version_recorded(self):
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(plistlib.loads(self.output.read_bytes()),
                         dict(self.info, MirageNativeDeviceBridge="enabled"))
        self.assertEqual(plistlib.loads(self.source.read_bytes()), self.info)
        expected = json.loads(self.expected_path.read_text())
        self.assertEqual(expected["CFBundleShortVersionString"], "0.2.0")
        self.assertEqual(expected["CFBundleVersion"], "27")
        self.assertEqual(expected["UIBackgroundModes"], ["location"])

    def test_missing_recording_metadata_rejected_at_source(self):
        for key in ("UIBackgroundModes", "NSLocationWhenInUseUsageDescription",
                    "NSLocalNetworkUsageDescription"):
            changed = copy.deepcopy(self.info)
            changed.pop(key)
            with self.subTest(key=key):
                self.assertNotEqual(self.prepare(info=changed).returncode, 0)
        for key, value in (("UIBackgroundModes", "location"), ("UIBackgroundModes", ["audio"]),
                           ("NSLocationWhenInUseUsageDescription", " "),
                           ("NSLocalNetworkUsageDescription", "$(UNRESOLVED)")):
            with self.subTest(key=key, value=value):
                self.assertNotEqual(self.prepare(info=dict(self.info, **{key: value})).returncode, 0)

    def test_stale_or_unresolved_version_metadata_rejected(self):
        for key, value in (("CFBundleShortVersionString", "0.1.0"), ("CFBundleVersion", "2"),
                           ("CFBundleShortVersionString", "$(WRONG_VERSION)")):
            with self.subTest(key=key, value=value):
                self.assertNotEqual(self.prepare(info=dict(self.info, **{key: value})).returncode, 0)
        for key, value in (("MARKETING_VERSION", "$(UNRESOLVED)"), ("CURRENT_PROJECT_VERSION", "26"),
                           ("GENERATE_INFOPLIST_FILE", "YES"), ("TARGETED_DEVICE_FAMILY", "1,2"),
                           ("PRODUCT_BUNDLE_IDENTIFIER", "com.other.app")):
            with self.subTest(key=key, value=value):
                self.assertNotEqual(self.prepare(settings=dict(self.settings, **{key: value})).returncode, 0)

    def test_source_outside_project_rejected(self):
        outside = self.root.parent / "outside-project.plist"
        result = self.prepare(settings=dict(self.settings, INFOPLIST_FILE=str(outside)))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("inside the iOS source tree", result.stderr)

    def test_archive_and_export_preserve_metadata_without_claiming_signing(self):
        info = self.compiled()
        for exported in (False, True):
            with self.subTest(exported=exported):
                result = self.verify(info, exported)
                self.assertEqual(result.returncode, 0, result.stderr)
                report = json.loads(self.report.read_text())
                self.assertTrue(report["metadata_verified"])
                self.assertNotIn("signature_verified", report)

    def test_archive_and_export_drop_or_change_rejected(self):
        info = self.compiled()
        for key, value in (("UIBackgroundModes", []), ("NSLocationWhenInUseUsageDescription", "old purpose"),
                           ("NSLocalNetworkUsageDescription", ""), ("CFBundleShortVersionString", "0.1.0"),
                           ("CFBundleVersion", "2"), ("UIDeviceFamily", [1, 2]),
                           ("CFBundleSupportedPlatforms", ["iPhoneSimulator"]),
                           ("MirageNativeDeviceBridge", "disabled")):
            for exported in (False, True):
                with self.subTest(key=key, exported=exported):
                    result = self.verify(dict(info, **{key: value}), exported)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(key, result.stderr)


if __name__ == "__main__":
    unittest.main()
