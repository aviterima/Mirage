# Signed iPhone distribution

Specification written 14 November 2026, before implementation. The owner requested an iPhone version a nontechnical recipient can install by following a link. This extends the existing native iPhone implementation; the device-output requirement and physical acceptance gates remain in force.

## Required result

A genuine Apple-signed device application, built from tested source with the native device bridge, delivered through a supported Apple distribution route. A ZIP of unsigned code, a preview-only build, or a website shortcut does not meet the request. The owner uses Windows and iPad; hosted macOS performs builds and signing.

Installation and operational readiness are separate gates. Signing alone does not configure Remote Pairing, developer services, the local tunnel, or prove location output/restoration. The current app still requires these prerequisites. The recipient's exact phone/iOS and the owner's Apple Developer membership have been requested and are not assumed.

## Implementation scope

- Keep the existing frozen unsigned engineering workflow and package unchanged.
- Add a manually invoked signed archive/export workflow using hosted macOS and the existing native bridge. It must run core, bridge and native tests against the source being archived.
- Accept signing material only through protected CI secrets. Use a temporary keychain and provisioning profile and clean them up even after failure. Never print credentials, pairing material, or provisioned device identifiers.
- Support Apple distribution export for registered-device testing and App Store Connect. TestFlight upload/review is a later explicit operation; an exported App Store Connect package is not itself an install link.
- Validate profile expiry, team, bundle identifier, distribution entitlement, certificate compatibility and, for registered-device delivery, recipient inclusion. Verify the actual exported signature and bundle; reject unsigned or preview-only outputs.
- Keep signed archives private. Registered-device profiles embed device identifiers; the existing public unsigned release does not authorize public disclosure of these identifiers.
- Generate an OTA manifest only from a verified registered-device package with a real HTTPS download URL. Manifest generation does not certify physical installation or operating readiness. Do not publish a recipient install page until its route and actual downloaded package are verified.
- Add the existing Mirage visual identity as a proper iPhone app icon.

## Verification and release gates

Portable tests must reject malformed/expired/mismatched signing metadata, unregistered recipients, unsafe URLs, unsigned bundles and corrupted archives. Hosted macOS must compile and test the unchanged native behavior and the Release device variant, then validate real signing/export when credentials exist.

Before recipient delivery, verify signing, exact downloaded bytes, sharing scope and expiration, actual installation on the supported iPhone/iOS, required setup, independently observed location output and Stop/restoration. Preserve the full physical acceptance matrix in `IPHONE_PARITY_2026-10-22.md`. Unrun checks remain unrun. No receipt, certificate, or passing simulator build substitutes for this evidence.

## Current external prerequisites

Repository secret names were inspected on 14 November 2026: no Apple signing secrets are configured. No Apple distribution integration was found in the plugin catalog. The owner confirmed there is no paid Apple Developer membership. The recipient has a Mac; phone/iOS and Mac details remain pending. The owner's own equipment is still Windows/iPad. No Apple enrollment purchase, agreement acceptance, device registration, TestFlight submission or public signed release has occurred.

Implementation and executed validation results will be appended here. This document is a specification, not a declaration that an installable release exists.

## Distribution route and limits

The initial candidate is registered-device distribution, which supports an HTTPS installation manifest without relying on TestFlight review. Apple requires an App ID, an Apple Distribution identity and a provisioning profile containing the intended iPhone. The signed profile inside the IPA includes registered-device identifiers, so that IPA must not be copied to the public engineering release.

TestFlight remains a separate possible route after App Store Connect upload and review. It is not a workaround for Mirage's developer-service prerequisites. A native location-output build must retain its actual function; a preview app would not satisfy this request.

The existing target is iOS 26.6.1. Upstream documents phone-only pairing on iOS 27 and later, and computer pairing on older versions. This is a possible future implementation route only after the intended recipient's OS is known and physical operation is validated. No upstream source code has been copied into Mirage for this change. Existing verify-only pairing behavior remains unchanged.

References checked 14 November 2026:

- [Apple: create an ad-hoc provisioning profile](https://developer.apple.com/help/account/provisioning-profiles/create-an-ad-hoc-provisioning-profile/)
- [Apple: registered-device distribution](https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices/)
- [Apple: hosted ad-hoc distribution](https://developer.apple.com/videos/play/wwdc2019/304/?time=474)
- [Apple: external TestFlight testers](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers/)
- [StikDebug: platform-dependent pairing requirements](https://github.com/StikDebug/StikDebug/blob/main/PAIRING.md)

The remaining owner actions are account access and recipient device identification, followed by signing and physical acceptance. Passwords, private keys, provisioning profiles and pairing records must not be pasted into chat or committed to source. Owner Mac access is not required: the Apple website/iPad can handle account steps and hosted macOS handles the build.

## Build operator instructions

`iPhone signed distribution preparation` defaults to `dry-run`. Push events always use that mode and never load signing credentials. It validates an unsigned Release archive with the real native bridge; this artifact is not installable. Manual `release-testing` and `app-store-connect` modes run the core, Rust and native tests before signing the exact source. App Store Connect export does not upload or invite testers automatically.

Signed runs require these protected CI secrets:

| Secret | Purpose |
| --- | --- |
| `MIRAGE_IOS_CERTIFICATE_P12_BASE64` | Base64 Apple Distribution identity including its private key |
| `MIRAGE_IOS_CERTIFICATE_PASSWORD` | Password protecting that P12 |
| `MIRAGE_IOS_PROFILE_BASE64` | Base64 matching Apple-issued distribution profile |
| `MIRAGE_IOS_TEAM_ID` | Owning Apple team |
| `MIRAGE_IOS_RECIPIENT_UDID` | Registered-device recipient; required for `release-testing` |
| `MIRAGE_IOS_PRIVATE_UPLOAD_URL` | Presigned HTTPS PUT for a new private object |
| `MIRAGE_IOS_PRIVATE_DOWNLOAD_URL` | Presigned HTTPS GET for that same object |

The transfer URLs must identify the same HTTPS authority and path, with different signing queries as needed. The destination must be new: an existing object is never overwritten. PUT must allow a harmless probe followed by replacement with the real package. The probe is downloaded with authorization and checked without its signing query before any private package bytes are sent. Single-use or immutable upload URLs do not support this contract. The complete resulting download must match its size and SHA-256. Bearer-link lifetime and intended sharing scope still require operator verification.

The private maintainer ZIP contains the signed IPA and sanitized signing receipt. Public Actions artifacts contain only sanitized receipts and unsigned build evidence. Never publish an ad-hoc IPA or its provisioning profile to the public engineering release.

After obtaining an appropriately scoped direct HTTPS URL for the signed IPA, create the installation manifest locally:

```sh
python3 ios/scripts/create-install-manifest.py \
  --receipt signing-validation.json --ipa Mirage.ipa \
  --ipa-url '<private HTTPS IPA URL>' --output manifest.plist
```

This downloads and verifies the hosted bytes, checks the local receipt/package match and rejects expired profiles and store-only packages. Treat the manifest as private because it contains the package URL. A later recipient page uses `itms-services://?action=download-manifest&url=` with the encoded HTTPS manifest URL. Verify actual install, device setup and location restoration before calling that page ready for a nontechnical recipient.

## Local verification

18 portable tests pass on Windows Python 3.12.14, covering signing metadata, archive corruption/path aliases, unsigned and preview-only rejection, HTTPS constraints, matching download bytes, private probe order, existing-object rejection and same-object upload/download binding. Git Bash syntax validation passes. The existing Android icon was rendered into an opaque 1024-pixel iPhone asset; PNG CRC/decompression checks pass and the image was visually inspected.

The first local run exposed Windows ZIP fixture path normalization; the fixture was corrected to preserve the malicious input and the rejection assertion remains. Review also identified private-transfer preflight ordering and upload/download object binding; both were corrected and have regression tests. Hosted compilation, actual Apple signing and physical acceptance are separate checks; their results are recorded below when executed.

## Hosted validation result

- [Engineering validation 43622384726](https://github.com/aviterima/Mirage/actions/runs/43622384726), source `29f8a796890cc396e7cc045ec9710226a55bf54f`, passed **74 tests**: 42 Swift core, 14 Rust, 16 native unit and 2 UI, with zero failures/errors/skips/ignored. Simulator and unsigned ARM64 device compilation passed. Native app/core/bridge behavior is unchanged; the only changes in those source directories are the three icon asset files. Current evidence reports iPhone 16 Pro Max / iOS 26.2 build 23C54, Xcode 16.4 build 16F6 and Swift 6.1.2.
- The first [distribution validation 43622384741](https://github.com/aviterima/Mirage/actions/runs/43622384741) compiled the Release archive successfully, then failed its required native-bridge metadata check: generated Info.plist omitted the custom build-setting key. This failure is retained. The fix supplies an explicit temporary base plist, merged with generated standard properties; the marker check and actual native-symbol check remain required.
- [Corrected distribution validation 43623009723](https://github.com/aviterima/Mirage/actions/runs/43623009723), source `4ceda398ffd96a215835989915f97ebc1bd0f8b5`, passed all 18 distribution tests, fresh ARM64 bridge compilation and the unsigned Release archive checks, including the explicit marker and real native symbols. This commit changes only distribution scripts and documentation relative to the engineering-tested source. It also fixes the optional codesign certificate-extraction argument to Apple's documented `--extract-certificates=<prefix>` form; actual signed execution remains unrun.
- Superseded runs 43622273057 and 43622273031 at `4fa9d68` were intentionally canceled before the final private-delivery review fixes; they are not test passes.

The engineering evidence ZIPs and the corrected distribution verification artifact were fully downloaded and matched recorded sizes/SHA-256 and ZIP CRC. The distribution receipt explicitly records signature/export/physical/recipient readiness as false. Private local evidence is in the entry workspace's `work/iphone-tap-install-2026-11-14` directory.

**Signing, signed export, real private transfer, recipient install-page publication, physical installation, device-service setup and location output/restoration are unrun.** There is still no ready-to-install recipient release. Apple membership, signing access and the intended device details remain prerequisites. Automated tests and successful unsigned Release compilation do not close these gates.
