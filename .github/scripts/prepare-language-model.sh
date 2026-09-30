#!/usr/bin/env bash
set -euo pipefail
model_file="$RUNNER_TEMP/Qwen3-0.6B-Q8_0.gguf"
curl -L --fail --retry 2 'https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/23749fefcc72300e3a2ad315e1317431b06b590a/Qwen3-0.6B-Q8_0.gguf' -o "$model_file"
echo "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031  $model_file" | sha256sum -c -
adb push "$model_file" /data/local/tmp/mirage-language.gguf
adb shell chmod 644 /data/local/tmp/mirage-language.gguf
adb shell run-as com.mirage.app mkdir -p files
adb shell run-as com.mirage.app cp /data/local/tmp/mirage-language.gguf files/Qwen3-0.6B-Q8_0.gguf
adb shell rm /data/local/tmp/mirage-language.gguf
