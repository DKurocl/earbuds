#!/usr/bin/env bash
# Run against the installed release on a connected Android device.
set -euo pipefail

runs=${1:-10}
if [[ ! "$runs" =~ ^[1-9][0-9]?$ ]]; then
    printf '%s\n' 'Usage: measure-startup.sh [iterations, 1–99]' >&2
    exit 2
fi

package=ua.olex.sonyanc
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell dumpsys package "$package" | awk '/versionName=/{print; exit}'
printf '%s\n' 'iteration,total_ms'
for ((i = 1; i <= runs; ++i)); do
    adb shell am force-stop "$package"
    result=$(adb shell am start -W -n "$package/.MainActivity")
    elapsed=$(awk '/^TotalTime: / {print $2}' <<< "$result" | tr -d '\r')
    if [[ ! "$elapsed" =~ ^[0-9]+$ ]]; then
        printf '%s\n' "$result" >&2
        exit 1
    fi
    printf '%s,%s\n' "$i" "$elapsed"
done
