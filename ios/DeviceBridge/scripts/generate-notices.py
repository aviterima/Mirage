#!/usr/bin/env python3
"""Generate source-backed notices from locked, target-filtered Cargo metadata.

No network calls and no third-party Python dependencies. License/NOTICE wording is
preserved; line endings and trailing whitespace are normalized in the output.
Identical sources are printed once and referenced by original source content hash.
Missing upstream copyright/license files are reported, never fabricated.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

NOTICE_NAME = re.compile(r"^(licen[cs]e|copying|notice|copyright|unlicense|authors)(?:$|[._-])", re.I)
ALLOWED_IDENTIFIERS = {"MIT", "Apache-2.0", "ISC", "BSD-2-Clause", "BSD-3-Clause", "Unicode-3.0", "Unlicense"}
MISSING_EXPECTED = {("ns-keyed-archive", "0.1.5"), ("plist-macro", "0.1.6")}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("metadata", type=Path)
    parser.add_argument("--bridge-dir", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    bridge = args.bridge_dir.resolve()
    metadata = json.loads(args.metadata.read_text(encoding="utf-8-sig"))
    nodes = {node["id"]: node for node in metadata["resolve"]["nodes"]}
    root = metadata["resolve"]["root"]
    reachable, pending = set(), [root]
    while pending:
        package_id = pending.pop()
        if package_id in reachable:
            continue
        reachable.add(package_id)
        for dep in nodes[package_id]["deps"]:
            if any(kind["kind"] in (None, "build") for kind in dep["dep_kinds"]):
                pending.append(dep["pkg"])
    packages = sorted((p for p in metadata["packages"] if p["id"] in reachable and p["id"] != root),
                      key=lambda p: (p["name"], p["version"]))
    texts: dict[str, str] = {}
    entries, unknown, missing = [], [], []
    rendered = [
        "Mirage iPhone native device bridge — third-party notices",
        "Generated from Cargo.lock and Cargo metadata filtered for aarch64-apple-ios.",
        "Includes normal and build dependencies; excludes dev-only/unreachable packages.",
        "These notices cover the Cargo dependency graph, including vendored license files.",
        "No GPL/AGPL application source is incorporated. No pairing material is included.",
        "License text is preserved below; repeated texts are referenced by SHA-256.",
        "Line endings/trailing whitespace are normalized; hashes identify original source files.",
        "Known provenance gaps: two crates declare permissive licenses but ship no",
        "license/copyright notice. Their manifest attribution is preserved without",
        "inventing an upstream copyright year or treating the missing notice as verified.",
        "The Rust toolchain/standard library and Apple SDK are outside Cargo.lock;",
        "their distribution notices must also be reviewed for a distributed final app.",
        "",
    ]
    for package in packages:
        name, version = package["name"], package["version"]
        directory = Path(package["manifest_path"]).parent
        expression = package.get("license") or "UNKNOWN"
        identifiers = set(re.findall(r"[A-Za-z][A-Za-z0-9.-]*", expression)) - {"AND", "OR", "WITH"}
        unsupported = sorted(identifiers - ALLOWED_IDENTIFIERS)
        if unsupported:
            unknown.append({"name": name, "version": version, "identifiers": unsupported})
        files = sorted((p for p in directory.rglob("*") if p.is_file() and NOTICE_NAME.match(p.name)
                        and ".git" not in p.parts and "target" not in p.parts), key=lambda p: p.as_posix())
        # The idevice crate is a workspace member; the MIT file lives at its root.
        if name == "idevice":
            inherited = directory.parent / "LICENSE.txt"
            if inherited.is_file():
                files.append(inherited)
        explicit = package.get("license_file")
        if explicit:
            candidate = directory / explicit
            if candidate.is_file() and candidate not in files:
                files.append(candidate)
        records = []
        for path in files:
            raw = path.read_bytes()
            text = raw.decode("utf-8")
            digest = hashlib.sha256(raw).hexdigest()
            texts.setdefault(digest, text)
            try:
                relative = path.relative_to(directory).as_posix()
            except ValueError:
                relative = "../" + path.name
            records.append({"path": relative, "sha256": digest})
        status = "notice-files-collected"
        if not records:
            status = "upstream-notice-missing"
            missing.append({"name": name, "version": version, "declared_license": expression,
                            "known_expected_gap": (name, version) in MISSING_EXPECTED})
        vcs_path = directory / ".cargo_vcs_info.json"
        vcs = json.loads(vcs_path.read_text(encoding="utf-8")) if vcs_path.exists() else None
        entry = {"name": name, "version": version, "source": package["source"],
                 "repository": package.get("repository"), "license": expression,
                 "authors": package.get("authors", []), "status": status,
                 "files": records, "published_vcs": vcs}
        entries.append(entry)
        rendered += [f"PACKAGE: {name} {version}", f"Declared license: {expression}",
                     f"Source: {package['source']}", f"Repository: {package.get('repository') or 'not declared'}"]
        if entry["authors"]:
            rendered.append("Upstream author attribution: " + "; ".join(entry["authors"]))
        if vcs:
            rendered.append("Published source revision: " + vcs["git"]["sha1"])
        if records:
            for record in records:
                rendered.append(f"Notice: {record['path']} — SHA256 {record['sha256']}")
        else:
            rendered += ["PROVENANCE GAP: published crate and exact source revision contain no license",
                         "or copyright file. Manifest license and named author are recorded above.",
                         "No copyright year or license notice has been invented for this package."]
        rendered.append("")
    rendered += ["FULL NOTICE TEXTS", "=================", ""]
    for digest, text in sorted(texts.items()):
        normalized = "\n".join(line.rstrip() for line in text.splitlines()).rstrip("\n")
        rendered += [f"BEGIN NOTICE SHA256 {digest}", normalized, f"END NOTICE SHA256 {digest}", ""]
    lock_hash = hashlib.sha256((bridge / "Cargo.lock").read_bytes()).hexdigest()
    audit = {"target": "aarch64-apple-ios", "scope": "normal-and-build-dependencies",
             "cargo_lock_sha256": lock_hash, "dependency_count": len(entries),
             "unique_notice_text_count": len(texts), "unknown_or_unsupported_license_identifiers": unknown,
             "missing_upstream_notices": missing, "packages": entries}
    (bridge / "THIRD-PARTY-NOTICES.txt").write_text("\n".join(rendered), encoding="utf-8", newline="\n")
    (bridge / "LICENSE-AUDIT.json").write_text(json.dumps(audit, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({key: value for key, value in audit.items() if key != "packages"}, indent=2))
    if unknown or any(not gap["known_expected_gap"] for gap in missing):
        raise SystemExit("Unexpected license identifier or missing notice; review required.")


if __name__ == "__main__":
    main()
