"""Validate compiled engineering-app metadata without signing or installation."""
import argparse
import hashlib
import json
import plistlib
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("bundle", type=Path)
parser.add_argument("platform", choices=["iPhoneOS", "iPhoneSimulator"])
parser.add_argument("report", type=Path)
parser.add_argument("--version", required=True)
parser.add_argument("--build", required=True)
args = parser.parse_args()

info = plistlib.loads((args.bundle / "Info.plist").read_bytes())
executable = args.bundle / info["CFBundleExecutable"]
keys = ["CFBundleIdentifier", "CFBundleShortVersionString", "CFBundleVersion",
        "UIDeviceFamily", "UIBackgroundModes", "NSLocationWhenInUseUsageDescription",
        "NSLocalNetworkUsageDescription", "CFBundleSupportedPlatforms"]
report = {"bundle": str(args.bundle), "metadata": {key: info.get(key) for key in keys},
          "executableBytes": executable.stat().st_size,
          "executableSHA256": hashlib.sha256(executable.read_bytes()).hexdigest(),
          "hasSignatureDirectory": (args.bundle / "_CodeSignature").exists(),
          "hasProvisioningProfile": (args.bundle / "embedded.mobileprovision").exists()}
args.report.parent.mkdir(parents=True, exist_ok=True)
args.report.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))

assert info["CFBundleIdentifier"] == "com.mirage.iphone", "Wrong bundle identifier"
assert info["CFBundleShortVersionString"] == args.version, "Wrong marketing version"
assert info["CFBundleVersion"] == args.build, "Wrong build number"
assert info["UIDeviceFamily"] == [1], "Expected iPhone-only device family"
assert "location" in info.get("UIBackgroundModes", []), "Missing location background mode"
assert str(info.get("NSLocationWhenInUseUsageDescription", "")).strip(), "Missing location purpose"
assert str(info.get("NSLocalNetworkUsageDescription", "")).strip(), "Missing local-network purpose"
assert args.platform in info["CFBundleSupportedPlatforms"], "Wrong build platform"
assert report["executableBytes"] > 0, "Missing executable"
assert not report["hasSignatureDirectory"], "Engineering build must remain unsigned"
assert not report["hasProvisioningProfile"], "Engineering build must not contain provisioning"
print("PASS: engineering bundle identity and location configuration")
