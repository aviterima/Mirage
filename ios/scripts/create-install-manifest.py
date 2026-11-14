#!/usr/bin/env python3
"""Generate an OTA manifest after verifying the hosted registered-device IPA."""
import argparse
import datetime as dt
import hashlib
import importlib.util
import ipaddress
import json
from pathlib import Path
import plistlib
import sys
import urllib.parse
import urllib.request

spec = importlib.util.spec_from_file_location("mirage_signing", Path(__file__).with_name("verify-signed-app.py"))
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)
ValidationError = signing.ValidationError
require = signing.require


def validate_url(url):
    require(isinstance(url, str) and not any(ord(c) <= 32 or ord(c) == 127 for c in url), "Invalid HTTPS delivery URL.")
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username
            and not parsed.password and not parsed.fragment and parsed.port in (None, 443),
            "Delivery requires HTTPS without embedded credentials, fragments or custom ports.")
    host = parsed.hostname.lower().rstrip(".")
    require(host != "localhost" and "." in host and not host.endswith((".localhost", ".local")),
            "A recipient-accessible HTTPS host is required.")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        require(address.is_global, "A recipient-accessible HTTPS host is required.")
    return url


class HTTPSRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        validate_url(new_url)
        return super().redirect_request(request, fp, code, message, headers, new_url)


def verify_hosted_file(url, receipt):
    opener = urllib.request.build_opener(HTTPSRedirectHandler())
    digest = hashlib.sha256()
    count = 0
    with opener.open(validate_url(url), timeout=30) as response:
        require(response.status == 200, "Hosted package did not return HTTP 200.")
        validate_url(response.url)
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            count += len(chunk)
            require(count <= receipt["ipa_bytes"], "Hosted package size does not match.")
            digest.update(chunk)
    require(count == receipt["ipa_bytes"] and digest.hexdigest() == receipt["ipa_sha256"],
            "Hosted package bytes do not match the verified IPA.")


def validate_receipt(receipt, ipa):
    require(receipt.get("schema") == 1 and receipt.get("method") == "release-testing",
            "OTA requires a verified registered-device package, not a TestFlight export.")
    require(receipt.get("signature_verified") is True and receipt.get("native_device_bridge_verified") is True,
            "Verified signature and native bridge evidence are required.")
    require(receipt.get("bundle_identifier") == signing.BUNDLE, "Receipt is for a different application.")
    require(signing.utc(dt.datetime.fromisoformat(receipt["profile_expires_utc"])) > dt.datetime.now(dt.timezone.utc),
            "Provisioning profile has expired.")
    require(Path(ipa).stat().st_size == receipt.get("ipa_bytes") and signing.sha256(ipa) == receipt.get("ipa_sha256"),
            "Local IPA does not match signing verification.")
    info = signing.inspect_ipa(ipa)
    require(info["CFBundleVersion"] == receipt.get("build") and info["CFBundleShortVersionString"] == receipt.get("version"),
            "Version metadata does not match the verified package.")


def manifest(receipt, ipa_url):
    validate_url(ipa_url)
    return {"items": [{"assets": [{"kind": "software-package", "url": ipa_url}],
                       "metadata": {"bundle-identifier": signing.BUNDLE, "bundle-version": receipt["build"],
                                    "kind": "software", "title": "Mirage"}}]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--ipa", type=Path, required=True)
    parser.add_argument("--ipa-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    receipt = json.loads(args.receipt.read_text(encoding="utf-8"))
    validate_receipt(receipt, args.ipa)
    verify_hosted_file(args.ipa_url, receipt)
    require(not args.output.exists(), "Manifest output already exists; use a new destination.")
    args.output.write_bytes(plistlib.dumps(manifest(receipt, args.ipa_url)))
    print("Manifest created from matching hosted bytes. Installation, access scope and operating readiness still require verification.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Network exceptions may contain private signed URLs; never print them.
        print(str(error) if isinstance(error, ValidationError) else "Manifest validation failed; inspect inputs privately.", file=sys.stderr)
        sys.exit(1)
