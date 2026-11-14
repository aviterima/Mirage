"""Portable rejection tests; these do not substitute for real Apple signing."""
import copy
import datetime as dt
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import plistlib
import stat
import tempfile
import unittest
from unittest import mock
import urllib.error
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


signing = module("distribution_signing", "verify-signed-app.py")
manifest = module("distribution_manifest", "create-install-manifest.py")
delivery = module("distribution_delivery", "deliver-private-package.py")
NOW = dt.datetime(2030, 1, 1, tzinfo=dt.timezone.utc)
TEAM = "SYNTHETIC1"
DEVICE = "synthetic-device-not-a-real-udid"
CERT = b"synthetic DER fixture; never a real Apple certificate"


def profile(method="release-testing"):
    value = {"UUID": "11111111-2222-3333-4444-555555555555", "TeamIdentifier": [TEAM],
             "ApplicationIdentifierPrefix": [TEAM], "ExpirationDate": NOW + dt.timedelta(days=30),
             "DeveloperCertificates": [CERT], "Entitlements": {
                 "application-identifier": TEAM + ".com.mirage.iphone",
                 "com.apple.developer.team-identifier": TEAM, "get-task-allow": False}}
    if method == "release-testing":
        value["ProvisionedDevices"] = [DEVICE]
    else:
        value["Entitlements"]["beta-reports-active"] = True
    return value


def ipa(path, changes=None, extra=None, unsigned=False):
    info = {"CFBundleIdentifier": signing.BUNDLE, "CFBundlePackageType": "APPL",
            "CFBundleSupportedPlatforms": ["iPhoneOS"], "UIDeviceFamily": [1],
            "CFBundleVersion": "12", "CFBundleShortVersionString": "0.1.0",
            "CFBundleExecutable": "Mirage", "MirageNativeDeviceBridge": "enabled"}
    info.update(changes or {})
    files = {"Payload/Mirage.app/Info.plist": plistlib.dumps(info), "Payload/Mirage.app/Mirage": b"synthetic executable"}
    if not unsigned:
        files.update({"Payload/Mirage.app/embedded.mobileprovision": b"synthetic CMS",
                      "Payload/Mirage.app/_CodeSignature/CodeResources": b"synthetic signature resources"})
    files.update(extra or {})
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in files.items():
            entry = zipfile.ZipInfo(name)
            # ZipInfo normalizes host separators on Windows; hostile fixtures
            # must retain the exact ZIP filename bytes being tested.
            entry.filename = name
            entry.orig_filename = name
            entry.create_system = 3
            entry.external_attr = (stat.S_IFREG | (0o755 if name.endswith("/Mirage") else 0o644)) << 16
            archive.writestr(entry, data)
    return info


class ProfileTests(unittest.TestCase):
    def check(self, value, method="release-testing", certificate=CERT, recipient=DEVICE):
        return signing.validate_profile(value, TEAM, method, certificate, recipient, NOW)

    def test_distribution_profiles_are_distinct(self):
        self.assertIn("profile_expires_utc", self.check(profile()))
        self.assertIn("application_identifier", self.check(profile("app-store-connect"), "app-store-connect", recipient=None))
        with self.assertRaises(ValueError):
            self.check(profile(), "app-store-connect")
        with self.assertRaises(ValueError):
            self.check(profile("app-store-connect"))

    def test_expiry_team_explicit_app_and_certificate_required(self):
        cases = []
        for key, value in (("ExpirationDate", NOW), ("ExpirationDate", "tomorrow"),
                           ("TeamIdentifier", ["OTHERTEAM1"]), ("UUID", "../profile"),
                           ("DeveloperCertificates", [b"another certificate"]), ("ProvisionsAllDevices", True)):
            changed = profile()
            changed[key] = value
            cases.append(changed)
        for key, value in (("get-task-allow", True), ("get-task-allow", 0),
                           ("application-identifier", TEAM + ".*"),
                           ("com.apple.developer.team-identifier", "OTHERTEAM1")):
            changed = profile()
            changed["Entitlements"][key] = value
            cases.append(changed)
        for index, changed in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(ValueError):
                self.check(changed)

    def test_missing_or_unregistered_recipient_rejected_without_disclosing_identifier(self):
        for recipient in (None, "private-unregistered-device"):
            with self.subTest(recipient=recipient), self.assertRaises(ValueError) as caught:
                self.check(profile(), recipient=recipient)
            self.assertNotIn("private-unregistered-device", str(caught.exception))

    def test_signed_entitlements_must_match_profile(self):
        value = profile()
        signing.validate_entitlements(value["Entitlements"], value, TEAM)
        changed = copy.deepcopy(value["Entitlements"])
        changed["get-task-allow"] = True
        with self.assertRaises(ValueError):
            signing.validate_entitlements(changed, value, TEAM)
        changed = copy.deepcopy(value["Entitlements"])
        changed["application-identifier"] += ".other"
        with self.assertRaises(ValueError):
            signing.validate_entitlements(changed, value, TEAM)


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "Mirage.ipa"

    def test_inspection_does_not_claim_cryptographic_verification(self):
        expected = ipa(self.path)
        self.assertEqual(signing.inspect_ipa(self.path), expected)
        self.assertNotIn("signature_verified", expected)

    def test_unsigned_or_preview_only_archives_rejected(self):
        ipa(self.path, unsigned=True)
        with self.assertRaises(ValueError):
            signing.inspect_ipa(self.path)
        for marker in (False, True, "disabled", "YES"):
            ipa(self.path, changes={"MirageNativeDeviceBridge": marker})
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                signing.inspect_ipa(self.path)

    def test_wrong_bundle_and_simulator_rejected(self):
        for changes in ({"CFBundleIdentifier": "com.other"}, {"CFBundleSupportedPlatforms": ["iPhoneSimulator"]},
                        {"UIDeviceFamily": [2]}, {"CFBundleExecutable": "../escape"}):
            ipa(self.path, changes=changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                signing.inspect_ipa(self.path)

    def test_traversal_and_normalized_aliases_rejected(self):
        for name in ("Payload/Mirage.app/../evil", "Payload/Mirage.app/./Info.plist",
                     "Payload/Mirage.app//Info.plist", "Payload/Mirage.app/info.plist",
                     "Payload/Mirage.app\\evil", "/Payload/evil", "Payload/Mirage.app/C:evil"):
            ipa(self.path, extra={name: b"bad"})
            with self.subTest(name=name), self.assertRaises(ValueError):
                signing.inspect_ipa(self.path)

    def test_corrupted_archive_rejected(self):
        ipa(self.path)
        contents = self.path.read_bytes()
        contents = contents.replace(b"synthetic executable", b"corrupted executable", 1)
        self.path.write_bytes(contents)
        with self.assertRaises(ValueError):
            signing.inspect_ipa(self.path)

    def test_receipt_cannot_be_reused_after_package_change(self):
        ipa(self.path)
        receipt = {"schema": 1, "method": "release-testing", "signature_verified": True,
                   "native_device_bridge_verified": True, "bundle_identifier": signing.BUNDLE,
                   "build": "12", "version": "0.1.0", "ipa_bytes": self.path.stat().st_size,
                   "ipa_sha256": signing.sha256(self.path),
                   "profile_expires_utc": (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=1)).isoformat()}
        manifest.validate_receipt(receipt, self.path)
        for changes in ({"method": "app-store-connect"}, {"signature_verified": False},
                        {"ipa_sha256": "0" * 64}, {"build": "13"},
                        {"profile_expires_utc": "2000-01-01T00:00:00+00:00"}):
            changed = dict(receipt, **changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                manifest.validate_receipt(changed, self.path)


class DeliveryTests(unittest.TestCase):
    def test_only_recipient_accessible_https_urls(self):
        valid = "https://downloads.example.com/mirage.ipa?signature=synthetic"
        self.assertEqual(manifest.validate_url(valid), valid)
        for url in ("http://downloads.example.com/a", "https://user:secret@example.com/a", "https://example.com/a#fragment",
                    "https://localhost/a", "https://127.0.0.1/a", "https://192.168.1.2/a", "https://example.com:8443/a",
                    "https://example.com/a\n", "file:///tmp/a", "https://device.local/a"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                manifest.validate_url(url)

    def test_manifest_uses_build_number_and_escapes_signed_query(self):
        data = manifest.manifest({"build": "12", "version": "0.1.0"}, "https://example.com/a?one=1&two=2")
        serialized = plistlib.dumps(data)
        self.assertIn(b"&amp;", serialized)
        self.assertEqual(plistlib.loads(serialized)["items"][0]["metadata"]["bundle-version"], "12")

    def test_hosted_byte_mismatch_rejected(self):
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.url = "https://example.com/a"
        response.read.side_effect = [b"bad", b""]
        opener = mock.Mock()
        opener.open.return_value = response
        with mock.patch.object(manifest.urllib.request, "build_opener", return_value=opener):
            with self.assertRaises(ValueError):
                manifest.verify_hosted_file("https://example.com/a", {"ipa_bytes": 3, "ipa_sha256": hashlib.sha256(b"yes").hexdigest()})

    def test_private_transfer_requires_urls_and_denied_anonymous_access(self):
        with mock.patch.dict(os.environ, {}, clear=True), self.assertRaises(ValueError):
            delivery.private_urls()
        opener = mock.Mock()
        opener.open.side_effect = urllib.error.HTTPError("https://example.com/a", 403, "denied", {}, None)
        delivery.require_anonymous_denied(opener, "https://example.com/a?secret=synthetic")
        self.assertEqual(opener.open.call_args[0][0].full_url, "https://example.com/a")
        opener.open.side_effect = None
        opener.open.return_value = mock.MagicMock()
        with self.assertRaises(ValueError):
            delivery.require_anonymous_denied(opener, "https://example.com/a?secret=synthetic")

    def test_private_presigns_must_target_the_same_object(self):
        upload = "https://storage.example.com/private/new.zip?signature=put"
        matching = "https://storage.example.com/private/new.zip?signature=get"
        with mock.patch.dict(os.environ, {"MIRAGE_IOS_PRIVATE_UPLOAD_URL": upload,
                                          "MIRAGE_IOS_PRIVATE_DOWNLOAD_URL": matching}, clear=True):
            self.assertEqual(delivery.private_urls(), [upload, matching])
            for wrong in ("https://other.example.com/private/new.zip?signature=get",
                          "https://storage.example.com/other.zip?signature=get"):
                with mock.patch.dict(os.environ, {"MIRAGE_IOS_PRIVATE_DOWNLOAD_URL": wrong}), self.assertRaises(ValueError):
                    delivery.private_urls()
        for path in ("", "/", "/a/../new.zip", "/a/./new.zip", "/a//new.zip", "/a%5Cnew.zip", "/a/%2e%2e/new.zip"):
            wrong = "https://storage.example.com" + path + "?signature=test"
            with mock.patch.dict(os.environ, {"MIRAGE_IOS_PRIVATE_UPLOAD_URL": wrong,
                                              "MIRAGE_IOS_PRIVATE_DOWNLOAD_URL": wrong}, clear=True), self.assertRaises(ValueError):
                delivery.private_urls()

    def test_private_transfer_rejects_all_redirects(self):
        with self.assertRaises(ValueError):
            delivery.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://other.example.com/a")
        with self.assertRaises(ValueError):
            manifest.HTTPSRedirectHandler().redirect_request(None, None, 302, "redirect", {}, "http://example.com/a")

    def test_private_probe_precedes_sensitive_upload_and_denial_blocks_it(self):
        sensitive = b"private signed IPA fixture"
        opener = mock.Mock()
        events = []
        with mock.patch.object(delivery, "require_missing", side_effect=lambda *a: events.append("missing")), \
                mock.patch.object(delivery, "put_bytes", side_effect=lambda o, u, p: events.append(("put", p))), \
                mock.patch.object(delivery, "verify_bytes", side_effect=lambda o, u, p: events.append(("get", p))), \
                mock.patch.object(delivery, "require_anonymous_denied", side_effect=lambda *a: events.append("denied")):
            delivery.transfer(opener, "upload", "download", sensitive)
        self.assertEqual(events[0], "missing")
        probe = events[1][1]
        self.assertNotEqual(probe, sensitive)
        self.assertEqual(events, ["missing", ("put", probe), ("get", probe), "denied",
                                  ("put", sensitive), "denied", ("get", sensitive)])
        with mock.patch.object(delivery, "require_missing"), mock.patch.object(delivery, "put_bytes") as put, \
                mock.patch.object(delivery, "verify_bytes"), \
                mock.patch.object(delivery, "require_anonymous_denied", side_effect=ValueError("public")):
            with self.assertRaises(ValueError):
                delivery.transfer(opener, "upload", "download", sensitive)
            self.assertEqual(put.call_count, 1)
            self.assertNotEqual(put.call_args[0][2], sensitive)

    def test_existing_destination_and_unauthorized_signed_get_rejected(self):
        opener = mock.Mock()
        opener.open.return_value = mock.MagicMock()
        with self.assertRaises(ValueError):
            delivery.require_missing(opener, "https://example.com/a?signature=test")
        for code in (403, 500):
            opener.open.side_effect = urllib.error.HTTPError("https://example.com/a", code, "error", {}, None)
            with self.subTest(code=code), self.assertRaises(ValueError):
                delivery.require_missing(opener, "https://example.com/a?signature=test")
        opener.open.side_effect = urllib.error.HTTPError("https://example.com/a", 404, "missing", {}, None)
        delivery.require_missing(opener, "https://example.com/a?signature=test")


if __name__ == "__main__":
    unittest.main()
