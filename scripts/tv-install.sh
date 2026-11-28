#!/usr/bin/env bash
# Installs or updates the signed Nuvio TV release on a TV over network ADB.
#   NUVIO_TV_IP=<ip[:port]> scripts/tv-install.sh [--apk <file>] [--tag <tag>]
# Connects, installs the APK with -r, launches it, then disconnects. Without
# --apk it downloads app-full-armeabi-v7a-release.apk from the GitHub release
# (the newest one, or --tag) into ~/nuvio/tv-install/apk-<tag>/.
# Pass ip:port when wireless debugging is on a non-default port (5555 default).
#
# Updates keep the signed-in account: app data lives in /data/data/com.nuvio.tv,
# and `install -r` of a build with the same release key keeps it. The script
# never uninstalls or clears data. Uninstalling deletes the account and asks
# Parth to sign in again, so `INSTALL_FAILED_UPDATE_INCOMPATIBLE` stops the run
# instead of being worked around here.
set -euo pipefail

ip="${NUVIO_TV_IP:-}"
apk=""
tag=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ip) ip="${2:-}"; shift 2 ;;
    --apk) apk="${2:-}"; shift 2 ;;
    --tag) tag="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$ip" ]] || { echo "set NUVIO_TV_IP or pass --ip <ip[:port]>" >&2; exit 2; }
[[ "$ip" =~ ^[A-Za-z0-9.:_-]+$ ]] || { echo "bad ip: $ip" >&2; exit 2; }

adb="${ADB:-adb}"
command -v "$adb" >/dev/null 2>&1 || adb="$HOME/Android/Sdk/platform-tools/adb"
command -v "$adb" >/dev/null 2>&1 || { echo "adb not found; set ADB=/path/to/adb" >&2; exit 1; }

if [[ -z "$apk" ]]; then
  if [[ -z "$tag" ]]; then
    tag="$(gh release view --repo kheniparth/NuvioTV --json tagName -q .tagName)"
  fi
  dir="$HOME/nuvio/tv-install/apk-$tag"
  apk="$dir/app-full-armeabi-v7a-release.apk"
  if [[ ! -f "$apk" ]]; then
    mkdir -p "$dir"
    gh release download "$tag" --repo kheniparth/NuvioTV \
      --pattern 'app-full-armeabi-v7a-release.apk' --dir "$dir"
  fi
fi
[[ -f "$apk" ]] || { echo "missing APK: $apk" >&2; exit 1; }

addr="$ip"
[[ "$addr" == *:* ]] || addr="$ip:5555"
trap '"$adb" disconnect "$addr" >/dev/null 2>&1 || true' EXIT

"$adb" connect "$addr"
state="$("$adb" -s "$addr" get-state 2>/dev/null || true)"
[[ "$state" == "device" ]] || { echo "not connected to $addr (state: $state)" >&2; exit 1; }

inst="$("$adb" -s "$addr" install -r "$apk" || true)"
echo "$inst"
if ! grep -q '^Success' <<<"$inst"; then
  echo "install failed on $addr" >&2
  case "$inst" in
    *UPDATE_INCOMPATIBLE*)
      echo "signature mismatch: this APK is not signed with the key already on the TV." >&2
      echo "Do NOT uninstall to work around it: that deletes the saved account. Ask Parth." >&2
      ;;
  esac
  exit 1
fi

start="$("$adb" -s "$addr" shell am start -W -n com.nuvio.tv/.launcher.AppIconDefault)"
echo "$start"
grep -q 'Status: ok' <<<"$start" || { echo "launch failed on $addr" >&2; exit 1; }
echo "Installed and launched $(basename "$apk") on $addr"
