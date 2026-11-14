#!/usr/bin/env python3
"""Narrow signing/IPA checks. Metadata checks alone never certify a signature."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import plistlib
import re
import stat
import subprocess
import sys
import tempfile
import unicodedata
import zipfile

BUNDLE = "com.mirage.iphone"
METHODS = ("release-testing", "app-store-connect")
BRIDGE_SYMBOLS = ("mirage_device_create", "mirage_device_connect", "mirage_device_set",
                  "mirage_device_clear", "mirage_device_destroy")


class ValidationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ValidationError(message)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def utc(value):
    require(isinstance(value, dt.datetime), "Missing or invalid profile expiration.")
    return value.replace(tzinfo=dt.timezone.utc) if value.tzinfo is None else value.astimezone(dt.timezone.utc)


def validate_profile(profile, team, method, certificate, recipient=None, now=None):
    """Return non-device metadata. Never include profile contents in errors."""
    require(isinstance(profile, dict), "Invalid provisioning profile.")
    require(method in METHODS, "Unsupported distribution method.")
    require(bool(re.fullmatch(r"[A-Z0-9]{10}", team)), "Invalid Apple team identifier.")
    require(profile.get("TeamIdentifier") == [team], "Provisioning team does not match.")
    expiration = utc(profile.get("ExpirationDate"))
    require(expiration > (now or dt.datetime.now(dt.timezone.utc)), "Provisioning profile has expired.")
    require(isinstance(profile.get("UUID"), str) and bool(re.fullmatch(
        r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}",
        profile["UUID"])), "Invalid provisioning profile UUID.")
    entitlements = profile.get("Entitlements")
    require(isinstance(entitlements, dict), "Missing profile entitlements.")
    require(entitlements.get("get-task-allow") is False, "A distribution profile is required.")
    require(entitlements.get("com.apple.developer.team-identifier") == team, "Entitlement team does not match.")
    prefixes = profile.get("ApplicationIdentifierPrefix")
    require(isinstance(prefixes, list) and len(prefixes) == 1 and isinstance(prefixes[0], str),
            "A single application identifier prefix is required.")
    app_id = prefixes[0] + "." + BUNDLE
    require(entitlements.get("application-identifier") == app_id, "An explicit Mirage app profile is required.")
    require(profile.get("ProvisionsAllDevices") is not True, "Enterprise profiles are not supported.")
    certs = profile.get("DeveloperCertificates")
    require(isinstance(certs, list) and bool(certificate) and certificate in certs,
            "Signing certificate does not belong to the profile.")
    devices = profile.get("ProvisionedDevices")
    if method == "release-testing":
        require(isinstance(devices, list) and len(devices) > 0 and all(isinstance(v, str) for v in devices),
                "Registered-device distribution profile is required.")
        require(bool(recipient) and recipient in devices, "Recipient device is not provisioned.")
    else:
        require(devices is None, "App Store Connect requires a store distribution profile.")
        require(entitlements.get("beta-reports-active") is True, "Store beta-reporting entitlement is required.")
    return {"profile_uuid": profile["UUID"], "profile_expires_utc": expiration.isoformat(),
            "application_identifier": app_id}


def validate_entitlements(entitlements, profile, team):
    require(isinstance(entitlements, dict), "Missing signed entitlements.")
    expected = profile["Entitlements"]
    for key in ("application-identifier", "com.apple.developer.team-identifier", "get-task-allow"):
        require(key in entitlements and entitlements[key] == expected[key], "Signed entitlements do not match the profile.")
    require(entitlements["com.apple.developer.team-identifier"] == team, "Signed team does not match.")
    require(entitlements["get-task-allow"] is False, "Debug entitlement found in distribution app.")


def inspect_ipa(path):
    """Inspect archive without extracting untrusted paths or assuming it is signed."""
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            require(len(names) == len(set(names)), "Duplicate IPA archive entries.")
            require(sum(e.file_size for e in entries) <= 1024 * 1024 * 1024, "IPA expands beyond the size limit.")
            destinations = set()
            for entry in entries:
                p = PurePosixPath(entry.filename)
                require(not p.is_absolute() and ".." not in p.parts and "\\" not in entry.filename
                        and "\\" not in entry.orig_filename
                        and ":" not in entry.filename and all(ord(c) >= 32 for c in entry.filename),
                        "Unsafe IPA archive path.")
                canonical = p.as_posix() + ("/" if entry.is_dir() else "")
                require(entry.filename == canonical, "Noncanonical IPA archive path.")
                destination = unicodedata.normalize("NFC", p.as_posix()).casefold()
                require(destination not in destinations, "Aliased IPA archive entries.")
                destinations.add(destination)
                require(not stat.S_ISLNK(entry.external_attr >> 16), "Symlinks are not supported in this iPhone package.")
                require(entry.filename.startswith(("Payload/", "SwiftSupport/", "Symbols/")),
                        "Unexpected top-level IPA content.")
            require(archive.testzip() is None, "IPA checksum failure.")
            app_roots = {PurePosixPath(n).parts[1] for n in names
                         if n.startswith("Payload/") and len(PurePosixPath(n).parts) >= 2}
            require(app_roots == {"Mirage.app"}, "Expected exactly one Mirage app.")
            prefix = "Payload/Mirage.app/"
            require(prefix + "Info.plist" in names, "App metadata is missing.")
            info = plistlib.loads(archive.read(prefix + "Info.plist"))
            require(isinstance(info, dict), "Invalid app metadata.")
            require(info.get("CFBundleIdentifier") == BUNDLE, "Unexpected app bundle identifier.")
            require(info.get("CFBundlePackageType") == "APPL", "Expected an application bundle.")
            require(info.get("CFBundleSupportedPlatforms") == ["iPhoneOS"], "Expected an iPhone device app.")
            require(info.get("UIDeviceFamily") in ([1], [1, 2]), "iPhone support is missing.")
            require(info.get("MirageNativeDeviceBridge") == "enabled", "Native device bridge build marker is missing.")
            for key in ("CFBundleVersion", "CFBundleShortVersionString"):
                require(isinstance(info.get(key), str) and bool(info[key]), "Missing app version metadata.")
            executable = info.get("CFBundleExecutable")
            require(isinstance(executable, str) and executable not in ("", ".", "..")
                    and "/" not in executable and "\\" not in executable, "Invalid app executable name.")
            require(prefix + executable in names, "App executable is missing.")
            require((archive.getinfo(prefix + executable).external_attr >> 16) & stat.S_IXUSR,
                    "App executable permission is missing.")
            require(prefix + "embedded.mobileprovision" in names and prefix + "_CodeSignature/CodeResources" in names,
                    "Unsigned app: provisioning profile or signature resources are missing.")
            return info
    except (zipfile.BadZipFile, plistlib.InvalidFileException, KeyError, RuntimeError, EOFError) as error:
        raise ValidationError("Invalid or damaged IPA archive.") from error


def run(*args):
    completed = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    require(completed.returncode == 0, "Apple signing or executable validation failed; inspect privately on the build host.")
    return completed.stdout


def verify_ipa(ipa, team, method, certificate, recipient, source):
    require(sys.platform == "darwin", "Cryptographic IPA verification requires macOS.")
    require(bool(re.fullmatch(r"[0-9a-f]{40}", source)), "A full source commit is required.")
    info = inspect_ipa(ipa)
    with tempfile.TemporaryDirectory(prefix="mirage-verify-") as temporary:
        folder = Path(temporary)
        with zipfile.ZipFile(ipa) as archive:
            archive.extractall(folder)
        app = folder / "Payload/Mirage.app"
        # A valid self-signed/ad-hoc code signature is insufficient for iPhone
        # distribution. Require Apple's distribution chain and the exact team.
        require(bool(re.fullmatch(r"[A-Z0-9]{10}", team)), "Invalid Apple team identifier.")
        requirement = ('anchor apple generic and certificate 1[field.1.2.840.113635.100.6.2.1] exists '
                       'and certificate leaf[field.1.2.840.113635.100.6.1.4] exists '
                       f'and certificate leaf[subject.OU] = "{team}" and identifier "{BUNDLE}"')
        run("codesign", "--verify", "--deep", "--strict", "-R", requirement, str(app))
        profile = plistlib.loads(run("security", "cms", "-D", "-i", str(app / "embedded.mobileprovision")))
        profile_metadata = validate_profile(profile, team, method, certificate, recipient)
        entitlements = plistlib.loads(run("codesign", "--display", "--entitlements", ":-", str(app)))
        validate_entitlements(entitlements, profile, team)
        certificate_prefix = folder / "signer"
        run("codesign", "--display", "--extract-certificates", str(certificate_prefix), str(app))
        require((folder / "signer0").read_bytes() == certificate, "Exported app uses a different signing certificate.")
        executable = app / info["CFBundleExecutable"]
        require(run("xcrun", "lipo", "-archs", str(executable)).decode().strip() == "arm64", "Expected ARM64 device executable.")
        build_info = run("xcrun", "vtool", "-show-build", str(executable)).decode()
        require(bool(re.search(r"platform\s+IOS\s*$", build_info, re.MULTILINE)), "Executable is not built for iPhoneOS.")
        symbols = run("nm", "-g", str(executable)).decode()
        require(all(re.search(r"\b_" + symbol + r"\s*$", symbols, re.MULTILINE) for symbol in BRIDGE_SYMBOLS),
                "Native device bridge is missing from the executable.")
        return {"schema": 1, "method": method, "bundle_identifier": BUNDLE,
                "version": info["CFBundleShortVersionString"], "build": info["CFBundleVersion"],
                "ipa_name": Path(ipa).name, "ipa_bytes": Path(ipa).stat().st_size, "ipa_sha256": sha256(ipa),
                "source_commit": source, "native_device_bridge_verified": True, "signature_verified": True,
                "profile_expires_utc": profile_metadata["profile_expires_utc"],
                "verified_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
                "physical_installation_verified": False, "device_output_verified": False,
                "testflight_uploaded": False, "ready_for_recipient": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare")
    verify = commands.add_parser("verify")
    for command in (prepare, verify):
        command.add_argument("--team", required=True)
        command.add_argument("--method", choices=METHODS, required=True)
        command.add_argument("--certificate", type=Path, required=True)
        command.add_argument("--output", type=Path, required=True)
    prepare.add_argument("--profile", type=Path, required=True)
    verify.add_argument("--ipa", type=Path, required=True)
    verify.add_argument("--source", required=True)
    args = parser.parse_args()
    certificate = args.certificate.read_bytes()
    recipient = os.environ.get("MIRAGE_IOS_RECIPIENT_UDID")
    if args.command == "prepare":
        profile = plistlib.loads(args.profile.read_bytes())
        metadata = validate_profile(profile, args.team, args.method, certificate, recipient)
        export = {"method": args.method, "destination": "export", "signingStyle": "manual",
                  "teamID": args.team, "signingCertificate": hashlib.sha1(certificate).hexdigest().upper(),
                  "provisioningProfiles": {BUNDLE: metadata["profile_uuid"]},
                  "manageAppVersionAndBuildNumber": False, "stripSwiftSymbols": False,
                  "thinning": "<none>"}
        args.output.write_bytes(plistlib.dumps(export))
    else:
        result = verify_ipa(args.ipa, args.team, args.method, certificate, recipient, args.source)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Signing validation completed; physical installation and device output remain unverified.")


if __name__ == "__main__":
    try:
        main()
    except (ValidationError, OSError, ValueError) as error:
        if isinstance(error, ValidationError):
            print(str(error), file=sys.stderr)
        else:
            print("Could not read or validate signing inputs. Inspect them privately.", file=sys.stderr)
        sys.exit(1)
