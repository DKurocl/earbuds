#!/usr/bin/env bash
# Collect a snapshot without changing app settings, counters, or the installed build.
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 || ! "$1" =~ ^(baseline|candidate)$ || ! "$2" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]]; then
    printf 'Usage: bash %s baseline|candidate scenario [output-directory]\n' "$0" >&2
    exit 2
fi

build=$1
scenario=$2
output=${3:-pixel-evidence}
package=ua.olex.sonyanc
model=$(adb shell getprop ro.product.model | tr -d '\r')
emulator=$(adb shell getprop ro.kernel.qemu | tr -d '\r')

if [[ "$model" != "Pixel 10 Pro XL" || "$emulator" == "1" ]]; then
    printf 'Expected a physical Pixel 10 Pro XL. ADB reported %s.\n' "$model" >&2
    exit 1
fi

version=$(adb shell dumpsys package "$package" | awk '/^[[:space:]]*versionName=/{print $1}' | tr -d '\r')
if [[ -z "$version" ]]; then
    printf 'Install Earbuds before collecting a snapshot.\n' >&2
    exit 1
fi

mkdir -p -- "$output"
directory=$(mktemp -d "$output/$build-$scenario-XXXXXXXX")
printf '%s\n' "Complete snapshot will be written to $directory"
{
    printf 'build=%s\nscenario=%s\nmodel=%s\n%s\n' "$build" "$scenario" "$model" "$version"
    printf 'capturedUtc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'android=%s\n' "$(adb shell getprop ro.build.version.release | tr -d '\r')"
    printf 'fingerprint=%s\n' "$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
} > "$directory/device.txt"
adb exec-out screencap -p > "$directory/screen.png"
adb shell dumpsys display > "$directory/display.txt"
adb shell dumpsys gfxinfo "$package" framestats > "$directory/frames.txt"
adb shell dumpsys meminfo "$package" > "$directory/memory.txt"
adb shell dumpsys battery > "$directory/phone-battery.txt"
printf 'complete\n' > "$directory/status.txt"
