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

Repository secret names were inspected on 14 November 2026: no Apple signing secrets are configured. No Apple distribution integration was found in the plugin catalog. Membership/access and the recipient's phone/iOS are pending user information. No Apple enrollment purchase, agreement acceptance, device registration, TestFlight submission or public signed release has occurred.

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
