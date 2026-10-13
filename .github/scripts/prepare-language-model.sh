#!/usr/bin/env bash
set -euo pipefail
model_file="$RUNNER_TEMP/gemma-4-E4B-it.litertlm"
curl -L --fail --retry 2 'https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm' -o "$model_file"
echo "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0  $model_file" | sha256sum -c -
adb shell run-as com.mirage.app mkdir -p no_backup
# Stream directly into private storage: staging a second 3.7 GB copy exhausts the emulator disk.
adb shell -T "run-as com.mirage.app sh -c 'cat > no_backup/gemma-4-E4B-it.litertlm'" < "$model_file"
device_hash=$(adb shell run-as com.mirage.app sha256sum no_backup/gemma-4-E4B-it.litertlm | cut -d ' ' -f 1 | tr -d '\r')
test "$device_hash" = '0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0'
