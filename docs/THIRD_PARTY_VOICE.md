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


## 0.12.0 candidate additions

- Qwen3-0.6B Q8_0: official Qwen repository, Apache 2.0. Source model artifact: https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/23749fefcc72300e3a2ad315e1317431b06b590a . Exact file: Qwen3-0.6B-Q8_0.gguf, 639,446,688 bytes. SHA-256: 9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031. Downloaded separately into app-private files; not public domain.
- llama.cpp: MIT license, pinned source commit 931351ea50dfdd3ee249606f655eef2e9a629daf at https://github.com/ggml-org/llama.cpp . Compiled into the Android app via CMake/NDK with CPU inference.
- License texts are packaged in assets/licenses. The prompt and action validation are Mirage code. No user instruction is sent to an external language-model API.
- Vosk recognition and Android TTS remain unchanged. The model integration does not establish transcription quality or offline availability of every installed Android TTS voice.
