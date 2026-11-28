#!/usr/bin/env bash
# Imports the stalker M3U and Xtream proxy Live TV sources into a Nuvio TV
# install over adb, through the app's shell-gated Live TV import provider.
#   NUVIO_TV_IP=<ip[:port]> scripts/tv-livetv.sh [--dry-run]
#   NUVIO_TV_SERIAL=emulator-5554 NUVIO_PACKAGE=com.nuviodebug.com scripts/tv-livetv.sh
# Values come from /mnt/d/stalker/.env ADDON_PUBLIC_URL + ADDON_TOKEN, or from
# the Enhanced .env NUVIO_M3U_URL / NUVIO_XTREAM_* keys. The payload goes to the
# device on stdin only. --dry-run prints it masked and sends nothing.
# Only Live TV settings are written; app data and the signed-in account stay.
set -euo pipefail

dry=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "usage: $0 [--dry-run]" >&2; exit 2 ;;
  esac
done

package="${NUVIO_PACKAGE:-com.nuvio.tv}"
profile="${NUVIO_PROFILE_ID:-1}"
ip="${NUVIO_TV_IP:-}"
serial="${NUVIO_TV_SERIAL:-}"

[[ "$profile" =~ ^[0-9]+$ ]] || { echo "bad profile id: $profile" >&2; exit 1; }
[[ "$package" =~ ^[A-Za-z0-9._]+$ ]] || { echo "bad package: $package" >&2; exit 1; }
if [[ -n "$ip" ]]; then
  [[ "$ip" =~ ^[A-Za-z0-9.:_-]+$ ]] || { echo "bad ip: $ip" >&2; exit 1; }
  addr="$ip"
  [[ "$addr" == *:* ]] || addr="$ip:5555"
elif [[ -n "$serial" ]]; then
  [[ "$serial" =~ ^[A-Za-z0-9:._-]+$ ]] || { echo "bad serial: $serial" >&2; exit 1; }
  addr="$serial"
else
  echo "no device: set NUVIO_TV_IP or NUVIO_TV_SERIAL" >&2
  exit 1
fi

sv() { grep -m1 "^$2=" "$1" | cut -d= -f2- | tr -d '\r' || true; }
stalker_env="/mnt/d/stalker/.env"
enhanced_env="$HOME/nuvio/nuvio-enhanced/.env"
public=""
token=""
m3u=""
xtream=""
user=""
pass=""
if [[ -f "$stalker_env" ]]; then
  public="$(sv "$stalker_env" ADDON_PUBLIC_URL)"
  token="$(sv "$stalker_env" ADDON_TOKEN)"
fi
if [[ -n "$public" && -n "$token" ]]; then
  public="${public%/}"
  m3u="$public/$token/playlist.m3u"
  xtream="$public/$token"
  user=test
  pass=test
elif [[ -f "$enhanced_env" ]]; then
  m3u="$(sv "$enhanced_env" NUVIO_M3U_URL)"
  xtream="$(sv "$enhanced_env" NUVIO_XTREAM_URL)"
  user="$(sv "$enhanced_env" NUVIO_XTREAM_USERNAME)"
  pass="$(sv "$enhanced_env" NUVIO_XTREAM_PASSWORD)"
  user="${user:-test}"
  pass="${pass:-test}"
fi
if [[ -z "$m3u" || -z "$xtream" ]]; then
  echo "missing ADDON_PUBLIC_URL/ADDON_TOKEN in $stalker_env or NUVIO_M3U_URL/NUVIO_XTREAM_URL in $enhanced_env" >&2
  exit 1
fi
mask_pass=""
[[ "$pass" == "test" ]] || mask_pass="$pass"

payload() {
  PROFILE_ID="$profile" M3U_URL="$m3u" XTREAM_URL="$xtream" \
  XTREAM_USER="$user" XTREAM_PASSWORD="$pass" python3 - "$@" <<'PY'
import base64, json, os, sys
payload = {
    "profileId": int(os.environ["PROFILE_ID"]),
    "m3uUrl": os.environ["M3U_URL"],
    "xtreamUrl": os.environ["XTREAM_URL"],
    "xtreamUser": os.environ["XTREAM_USER"],
    "xtreamPassword": os.environ["XTREAM_PASSWORD"],
}
text = json.dumps(payload, separators=(",", ":"))
if sys.argv[1:] == ["--mask"]:
    for secret in (os.environ["MASK_TOKEN"], os.environ["MASK_PASSWORD"]):
        if secret:
            text = text.replace(secret, "***")
    print(text)
else:
    print(base64.b64encode(text.encode()).decode())
PY
}
mask_token="$token"

if [[ "$dry" -eq 1 ]]; then
  MASK_TOKEN="$mask_token" MASK_PASSWORD="$mask_pass" payload --mask
  echo "would import profile $profile into $package on $addr (dry run; sent nothing)"
  exit 0
fi

if [[ -n "${ADB:-}" ]]; then adb_bin="$ADB"
elif command -v adb >/dev/null 2>&1; then adb_bin="$(command -v adb)"
elif [[ -x "$HOME/Android/Sdk/platform-tools/adb" ]]; then adb_bin="$HOME/Android/Sdk/platform-tools/adb"
else echo "adb not found; set ADB=/path/to/adb" >&2; exit 1; fi

if [[ -n "$ip" ]]; then
  "$adb_bin" connect "$addr" >/dev/null 2>&1 || true
  trap '"$adb_bin" disconnect "$addr" >/dev/null 2>&1 || true' EXIT
fi

state="$("$adb_bin" -s "$addr" get-state 2>/dev/null || true)"
[[ "$state" == "device" ]] || { echo "not connected to $addr (state: ${state:-unknown})" >&2; exit 1; }

b64="$(payload)"
out="$(printf 'content call --uri content://%s.livetvimport --method import --arg %s\n' "$package" "$b64" \
  | "$adb_bin" -s "$addr" shell 2>&1 || true)"

if grep -q "result=updated" <<<"$out"; then
  echo "Live TV sources written for profile $profile on $addr."
  exit 0
fi
if grep -q "result=unchanged" <<<"$out"; then
  echo "Live TV sources on $addr are already up to date."
  exit 0
fi
if grep -qE "SecurityException|refused" <<<"$out"; then
  echo "refused by $package on $addr." >&2
  exit 1
fi
if grep -q "result=error:profile" <<<"$out"; then
  echo "profile $profile does not exist on $addr." >&2
  exit 1
fi
if grep -q "result=error:" <<<"$out"; then
  reason="$(grep -o 'result=error:[a-z:-]*' <<<"$out" | head -n1)"
  echo "import failed on $addr ($reason); rerun when the app is ready." >&2
  exit 1
fi
if grep -qiE "could not find provider|unknown provider|unknown authority|unknown uri|error while accessing provider" <<<"$out"; then
  echo "$package on $addr has no Live TV import; install a build with TEC-425 (scripts/tv-install.sh)." >&2
  exit 1
fi
echo "could not read the import result from $addr (is $package installed?)" >&2
exit 1
