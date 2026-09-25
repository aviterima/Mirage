# Permanent signing and the one-time installation reset

The owner accepts losing the old installation's data once. Do not uninstall until a replacement APK signed by the permanent identity has been built and verified.

## Provision once

On a trusted administrator machine with Java installed:

```sh
umask 077
keytool -genkeypair -keystore mirage-permanent-debug.keystore -storetype JKS -alias androiddebugkey -keyalg RSA -keysize 3072 -validity 10000 -storepass android -keypass android -dname "CN=Mirage Android"
keytool -exportcert -keystore mirage-permanent-debug.keystore -alias androiddebugkey -storepass android | openssl dgst -sha256
openssl base64 -A -in mirage-permanent-debug.keystore -out mirage-permanent-debug.base64
```

This preserves the existing debug APK build type and Android's expected debug key alias/password. The private key file and its base64 representation are secrets; base64 is not encryption. Never commit or publish either file. Store a recovery copy in an encrypted password manager or other durable secret vault.

In GitHub repository Settings → Secrets and variables → Actions:
- Secret `MIRAGE_DEBUG_KEYSTORE_BASE64`: the base64 file contents.
- Variable `MIRAGE_SIGNING_CERT_SHA256`: the 64-character hexadecimal SHA-256 certificate digest printed above.

Remove the temporary base64 file after secure provisioning. Keep the signing keystore backed up securely. Never regenerate it for routine builds or change the certificate pin to make an unexpected certificate pass.

## Build behavior

The workflow restores the same key before Gradle runs, checks its certificate against the pin, and verifies the finished APK signature against the same pin before upload or release. Missing or mismatched credentials block distribution. Fork PR builds without secrets will fail at this gate; they are not installable distribution builds.

This configuration requires the one-time secret provisioning above. The workflow change alone does not provision a key or produce a usable APK.

## Data preservation and acceptance check

Keep application ID `com.mirage.app`, preserve existing preference files and keys, retain the signing identity, and increase versionCode for each distributed update. Saved scenarios currently live in `mirage_scenarios`; the user API key and install ID live in `mirage_keys`. Normal in-place updates retain these stores.

For the one-time reset, install the verified permanent-key APK after uninstalling the old installation. Then save a route, snap, itinerary and user key. Build a subsequent APK with a higher versionCode and the same signing certificate. Install it over the first without uninstalling and verify every saved item and key remains. This device acceptance check has not yet been performed.

An uninstall still deletes app-private data. An encrypted external backup/restore feature is separate work and is not implemented by this signing change. Unsaved in-memory plans are not made persistent by signing.

If a Google Maps key has Android application restrictions, authorize this permanent signing certificate's SHA-1 fingerprint for package `com.mirage.app` in its Google Cloud key configuration.
