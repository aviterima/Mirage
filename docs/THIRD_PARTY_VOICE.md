# Voice dependencies

- Vosk Android 0.3.75 — Alpha Cephei, Apache License 2.0.
  Source: https://github.com/alphacep/vosk-api
- Vosk small US English model 0.15 — Apache License 2.0, per the upstream model catalog.
  Catalog: https://alphacephei.com/vosk/models
  Build input: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
- JNA 5.18.1 Android AAR — JNA project; dual LGPL 2.1 or later / Apache License 2.0.
  Use under Apache License 2.0. Source: https://github.com/java-native-access/jna
- Full Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

The Gradle task preserves model archive files when extracting into assets. Before
public distribution, include the full dependency notices/licenses in the app's
About/open-source notices screen and verify the exact downloaded artifacts.
This candidate has not been built or approved for public distribution.
