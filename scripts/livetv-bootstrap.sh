#!/usr/bin/env bash
# Writes Stalker, M3U and Xtream from /mnt/d/stalker/.env into NuvioTV's Live TV
# DataStore on a debuggable build (run-as). Default device is the emulator.
#   bash scripts/livetv-bootstrap.sh [--dry-run] [--device]
# --dry-run prints the JSON it would write with the token and MAC masked.
# A real run keeps an existing isEnabled and any other playlists.
set -euo pipefail

dry=0
allow_device=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=1 ;;
    --device) allow_device=1 ;;
    *) echo "usage: $0 [--dry-run] [--device]" >&2; exit 2 ;;
  esac
done

serial="${NUVIO_TV_SERIAL:-emulator-5554}"
package="${NUVIO_PACKAGE:-com.nuviodebug.com}"
profile="${NUVIO_PROFILE_ID:-1}"
[[ "$serial" =~ ^[A-Za-z0-9:._-]+$ ]] || { echo "bad serial" >&2; exit 1; }
[[ "$package" =~ ^[A-Za-z0-9._]+$ ]] || { echo "bad package" >&2; exit 1; }
[[ "$profile" =~ ^[0-9]+$ ]] || { echo "bad profile" >&2; exit 1; }
case "$serial" in
  emulator-*) ;;
  *)
    if [[ "$allow_device" -ne 1 ]]; then
      echo "refusing serial $serial (not an emulator). Pass --device to override." >&2
      exit 1
    fi
    ;;
esac

root="$(cd "$(dirname "$0")/.." && pwd)"
env_file="/mnt/d/stalker/.env"
[[ -f "$env_file" ]] || { echo "missing $env_file" >&2; exit 1; }
sv() { grep -m1 "^$1=" "$env_file" | cut -d= -f2- | tr -d '\r' || true; }

host="$(sv STALKER_HOST)"; host="${host#http://}"; host="${host#https://}"; host="${host%/}"
mac="$(sv STALKER_MAC)"
public="$(sv ADDON_PUBLIC_URL)"; public="${public%/}"
token="$(sv ADDON_TOKEN)"
[[ -n "$host" && -n "$mac" && -n "$public" && -n "$token" ]] \
  || { echo "$env_file is missing STALKER_HOST, STALKER_MAC, ADDON_PUBLIC_URL or ADDON_TOKEN" >&2; exit 1; }

if [[ "$profile" == "1" ]]; then
  file="files/datastore/live_tv_settings.preferences_pb"
else
  file="files/datastore/live_tv_settings_p${profile}.preferences_pb"
fi

export PORTAL="http://$host/stalker_portal/server/load.php"
export MAC="$mac"
export M3U="$public/$token/playlist.m3u"
export XT_URL="$public/$token"
export XT_USER=test XT_PASS=test TOKEN="$token"

if [[ "$dry" -eq 1 ]]; then
  python3 "$root/scripts/livetv-bootstrap.py" --dry-run
  echo "serial=$serial package=$package file=$file"
  exit 0
fi

if [[ -n "${ADB:-}" ]]; then adb_bin="$ADB"
elif command -v adb >/dev/null 2>&1; then adb_bin=adb
elif [[ -x "$HOME/Android/Sdk/platform-tools/adb" ]]; then adb_bin="$HOME/Android/Sdk/platform-tools/adb"
else echo "adb not found" >&2; exit 1; fi
adb() { "$adb_bin" -s "$serial" "$@"; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
if ! adb exec-out run-as "$package" cat "$file" >"$tmp/cur" 2>"$tmp/err"; then
  if grep -q "No such file" "$tmp/err"; then
    : >"$tmp/cur"
  else
    echo "could not read Live TV settings on $serial (install a debuggable $package build)" >&2
    exit 1
  fi
fi

set +e
python3 "$root/scripts/livetv-bootstrap.py" <"$tmp/cur" >"$tmp/new"
rc=$?
set -e
case "$rc" in
  0) ;;
  3) echo "Live TV sources on $serial are already up to date."; exit 0 ;;
  *) echo "could not build Live TV settings" >&2; exit 1 ;;
esac

# The app keeps the file in memory and would overwrite it, so stop it first.
in_front=0
if adb shell dumpsys activity activities 2>/dev/null | grep -q "topResumedActivity=.*${package}/"; then
  in_front=1
fi
adb shell am force-stop "$package"
base64 -w 0 "$tmp/new" | adb shell "run-as $package sh -c 'mkdir -p files/datastore && base64 -d > $file.tmp && mv $file.tmp $file && rm -f $file.bak'"
if [[ "$in_front" -eq 1 ]]; then
  adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  echo "Live TV sources written for profile $profile on $serial and the app reopened."
else
  echo "Live TV sources written for profile $profile on $serial."
fi
