#!/usr/bin/env python3
"""Send a maintainer package to preconfigured private HTTPS object storage.

This is not an end-user install link. Never print pre-signed URLs or HTTP errors.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

spec = importlib.util.spec_from_file_location("mirage_manifest", Path(__file__).with_name("create-install-manifest.py"))
manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest)
require = manifest.require
ValidationError = manifest.ValidationError


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise ValidationError("Private delivery redirects are not permitted.")


def private_urls():
    urls = [os.environ.get(name, "") for name in ("MIRAGE_IOS_PRIVATE_UPLOAD_URL", "MIRAGE_IOS_PRIVATE_DOWNLOAD_URL")]
    for url in urls:
        manifest.validate_url(url)
        require(bool(urllib.parse.urlsplit(url).query), "Private delivery requires pre-signed HTTPS object URLs.")
    upload, download = [urllib.parse.urlsplit(url) for url in urls]
    for parsed in (upload, download):
        decoded = urllib.parse.unquote(parsed.path)
        require(decoded.startswith("/") and len(decoded) > 1 and not decoded.endswith("/")
                and "\\" not in decoded and "%" not in decoded and ":" not in decoded
                and not any(ord(c) <= 32 or ord(c) == 127 for c in decoded)
                and ".." not in PurePosixPath(decoded).parts and PurePosixPath(decoded).as_posix() == decoded,
                "Private delivery requires a canonical object path.")
    require((upload.scheme, upload.hostname.lower(), upload.port or 443, upload.path)
            == (download.scheme, download.hostname.lower(), download.port or 443, download.path),
            "Private upload and download must address the same HTTPS object; only signed queries may differ.")
    return urls


def require_anonymous_denied(opener, download_url):
    parsed = urllib.parse.urlsplit(download_url)
    anonymous = urllib.parse.urlunsplit(parsed._replace(query=""))
    try:
        with opener.open(urllib.request.Request(anonymous, method="GET"), timeout=30):
            raise ValidationError("Object is accessible without the signed query; private delivery refused.")
    except urllib.error.HTTPError as error:
        require(error.code in (401, 403, 404), "Could not confirm denial without the signed query.")


def require_missing(opener, download_url):
    try:
        with opener.open(download_url, timeout=30):
            raise ValidationError("Refusing to overwrite an existing private delivery object.")
    except urllib.error.HTTPError as error:
        require(error.code == 404, "A new object with a valid signed GET URL is required.")


def put_bytes(opener, upload_url, payload):
    request = urllib.request.Request(upload_url, data=payload, method="PUT", headers={"Content-Type": "application/zip"})
    with opener.open(request, timeout=60) as response:
        require(response.status in (200, 201, 204), "Private upload was not accepted.")


def verify_bytes(opener, download_url, payload):
    fetched = hashlib.sha256()
    count = 0
    with opener.open(download_url, timeout=60) as response:
        require(response.status == 200, "Private download did not return HTTP 200.")
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            count += len(block)
            require(count <= len(payload), "Private download size mismatch.")
            fetched.update(block)
    require(count == len(payload) and fetched.digest() == hashlib.sha256(payload).digest(),
            "Private download bytes do not match.")


def transfer(opener, upload_url, download_url, payload):
    # A wrongly public destination must only ever receive these harmless bytes.
    # Require a new object; some single-use/immutable upload URLs intentionally
    # fail this two-PUT contract instead of weakening the privacy check.
    require_missing(opener, download_url)
    probe = b"Mirage private delivery access probe\n" + os.urandom(32)
    put_bytes(opener, upload_url, probe)
    verify_bytes(opener, download_url, probe)
    require_anonymous_denied(opener, download_url)
    put_bytes(opener, upload_url, payload)
    require_anonymous_denied(opener, download_url)
    verify_bytes(opener, download_url, payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-config", action="store_true")
    parser.add_argument("--ipa", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    upload_url, download_url = private_urls()
    if args.check_config:
        print("Private HTTPS transfer URLs configured; actual access remains to be checked.")
        return
    require(all((args.ipa, args.receipt, args.output)), "IPA, receipt and output are required.")
    receipt = json.loads(args.receipt.read_text(encoding="utf-8"))
    require(receipt.get("schema") == 1 and receipt.get("signature_verified") is True
            and receipt.get("native_device_bridge_verified") is True, "Verified signed IPA is required.")
    require(args.ipa.stat().st_size == receipt.get("ipa_bytes") and manifest.signing.sha256(args.ipa) == receipt.get("ipa_sha256"),
            "IPA changed after signing verification.")
    opener = urllib.request.build_opener(NoRedirect())
    with tempfile.TemporaryDirectory(prefix="mirage-delivery-") as temporary:
        package = Path(temporary) / "Mirage-private-maintainer-package.zip"
        with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(args.ipa, "Mirage.ipa")
            archive.write(args.receipt, "signing-validation.json")
        # This small native app is bounded; fail rather than buffer an unexpectedly large build.
        require(package.stat().st_size <= 128 * 1024 * 1024, "Private package exceeds the transfer size limit.")
        payload = package.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        transfer(opener, upload_url, download_url, payload)
        result = {"schema": 1, "package_bytes": len(payload), "package_sha256": digest,
                  "download_bytes_verified": True, "stripped_query_access_denied": True,
                  "nonsecret_probe_verified_before_upload": True,
                  "access": "Bearer signed URL; recipient identity and URL lifetime require separate operator verification.",
                  "recipient_install_link_created": False, "ready_for_recipient": False}
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Private maintainer transfer verified. Recipient installation link has not been created.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error) if isinstance(error, ValidationError) else "Private delivery failed; URLs and server errors were withheld.", file=sys.stderr)
        sys.exit(1)
