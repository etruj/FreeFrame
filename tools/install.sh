#!/usr/bin/env bash
# Installs (or updates) FreeFrame on a tablet over adb and turns on the
# always-on settings. Photos and settings live on the tablet and survive updates.
#
#   tools/install.sh                     installs build/FreeFrame.apk
#   tools/install.sh FreeFrame-4.0.apk   installs a downloaded release
#
# The tablet must be plugged in with USB debugging on (see README).
set -euo pipefail
cd "$(dirname "$0")/.."
APK="${1:-build/FreeFrame.apk}"
[ -f "$APK" ] || { echo "No APK at $APK. Build one (tools/build-apk.sh) or pass a downloaded one."; exit 1; }

adb install -r "$APK"
# Keep the screen on whenever it has power, and stop pop-up notifications
# from drawing over the frame.
adb shell settings put global stay_on_while_plugged_in 3
adb shell settings put global heads_up_notifications_enabled 0
# Keep Wi-Fi awake, so the photo manager stays reachable.
adb shell settings put global wifi_sleep_policy 2
adb shell am start -n local.photoframe/.MainActivity
IP=$(adb shell ip -4 addr show wlan0 | grep -oE 'inet [0-9.]+' | cut -d' ' -f2)
echo "FreeFrame is running. Photo manager: http://$IP:8080/"
