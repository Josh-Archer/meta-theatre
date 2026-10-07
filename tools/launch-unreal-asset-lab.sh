#!/usr/bin/env bash
set -euo pipefail

engine_root="$HOME/Applications/UnrealEngine/5.8.3"
project_file="$HOME/Documents/Unreal Projects/MetaTheatreAssetLab/MetaTheatreAssetLab.uproject"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$HOME/Android/UnrealSdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export NDKROOT="$ANDROID_HOME/ndk/27.2.12479018"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

exec "$engine_root/Engine/Binaries/Linux/UnrealEditor" "$project_file" "$@"
