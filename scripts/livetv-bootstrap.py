#!/usr/bin/env python3
"""Merge stalker Live TV sources into a NuvioTV DataStore preferences file.

String values are the JSON LiveTvSettingsDataStore reads (playlists_blob,
xtream_settings, stalker_settings). Stdin is the current preferences protobuf
(empty is fine); stdout is the replacement. Exit 3 means nothing changed.
--dry-run prints that JSON with the MAC and TOKEN masked.
"""
import json
import os
import re
import sys

PLAYLIST_ID = "stalker-m3u"
KEYS = ("stalker_settings", "xtream_settings", "playlists_blob")


def dumps(obj):
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False)


def flag(obj):
    # Missing or unreadable isEnabled stays on, matching LiveTvSettings.kt.
    if isinstance(obj, dict) and isinstance(obj.get("isEnabled"), bool):
        return obj["isEnabled"]
    return True


def stalker_json(portal, mac, enabled):
    return dumps({
        "portalUrl": portal,
        "macAddress": mac.upper(),
        "username": "",
        "password": "",
        "isEnabled": enabled,
    })


def xtream_json(url, user, password, enabled):
    return dumps({
        "serverUrl": url,
        "username": user,
        "password": password,
        "isEnabled": enabled,
    })


def playlist(m3u, enabled):
    return {
        "id": PLAYLIST_ID,
        "name": "Stalker M3U",
        "type": "Url",
        "source": m3u,
        "isEnabled": enabled,
    }


def merge(existing, env):
    out = dict(existing)
    if env["PORTAL"] and env["MAC"]:
        enabled = True
        old = existing.get("stalker_settings")
        if old:
            try:
                enabled = flag(json.loads(old))
            except json.JSONDecodeError:
                enabled = True
        out["stalker_settings"] = stalker_json(env["PORTAL"], env["MAC"], enabled)
    if env["XT_URL"]:
        enabled = True
        old = existing.get("xtream_settings")
        if old:
            try:
                enabled = flag(json.loads(old))
            except json.JSONDecodeError:
                enabled = True
        out["xtream_settings"] = xtream_json(
            env["XT_URL"], env["XT_USER"] or "test", env["XT_PASS"] or "test", enabled
        )
    if env["M3U"]:
        enabled = True
        merged = []
        seen = False
        parsed = []
        old = existing.get("playlists_blob")
        if old:
            try:
                loaded = json.loads(old)
                if isinstance(loaded, list):
                    parsed = loaded
            except json.JSONDecodeError:
                parsed = []
        for item in parsed:
            if not isinstance(item, dict):
                continue
            if item.get("id") == PLAYLIST_ID:
                enabled = flag(item)
                merged.append(playlist(env["M3U"], enabled))
                seen = True
            else:
                merged.append(item)
        if not seen:
            merged.append(playlist(env["M3U"], enabled))
        out["playlists_blob"] = dumps(merged)
    changed = any(out.get(key) != existing.get(key) for key in KEYS)
    return out, changed


def varint(n):
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        if n:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def length_field(field, payload):
    return varint((field << 3) | 2) + varint(len(payload)) + payload


def encode_prefs(values):
    chunks = []
    for key, value in values.items():
        if not isinstance(value, str):
            raise ValueError("non-string")
        # Value.string is field 5, wrapped as map-entry field 2.
        entry = length_field(1, key.encode()) + length_field(2, length_field(5, value.encode()))
        chunks.append(length_field(1, entry))
    return b"".join(chunks)


def read_varint(buf, i):
    n = shift = 0
    while True:
        if i >= len(buf) or shift > 63:
            raise ValueError("truncated")
        byte = buf[i]
        i += 1
        n |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return n, i
        shift += 7


def take_len(buf, i):
    ln, i = read_varint(buf, i)
    if ln < 0 or i + ln > len(buf):
        raise ValueError("truncated")
    return buf[i:i + ln], i + ln


def skip(buf, i, wire):
    if wire == 0:
        _, i = read_varint(buf, i)
        return i
    if wire == 1:
        if i + 8 > len(buf):
            raise ValueError("truncated")
        return i + 8
    if wire == 5:
        if i + 4 > len(buf):
            raise ValueError("truncated")
        return i + 4
    if wire == 2:
        _, i = take_len(buf, i)
        return i
    raise ValueError("wire")


def parse_value(msg):
    i = 0
    found = None
    while i < len(msg):
        tag, i = read_varint(msg, i)
        field, wire = tag >> 3, tag & 7
        if field == 5 and wire == 2:
            raw, i = take_len(msg, i)
            found = raw.decode()
        else:
            raise ValueError("non-string preference")
    if found is None:
        raise ValueError("missing string")
    return found


def parse_entry(entry):
    i = 0
    name = value = None
    while i < len(entry):
        tag, i = read_varint(entry, i)
        field, wire = tag >> 3, tag & 7
        if wire != 2:
            i = skip(entry, i, wire)
            continue
        raw, i = take_len(entry, i)
        if field == 1:
            name = raw.decode()
        elif field == 2:
            value = parse_value(raw)
    if name is None or value is None:
        raise ValueError("bad entry")
    return name, value


def parse_prefs(buf):
    if not buf:
        return {}
    i = 0
    out = {}
    while i < len(buf):
        tag, i = read_varint(buf, i)
        field, wire = tag >> 3, tag & 7
        if field == 1 and wire == 2:
            raw, i = take_len(buf, i)
            key, value = parse_entry(raw)
            out[key] = value
        else:
            i = skip(buf, i, wire)
    return out


def env_from_os():
    env = {key: os.environ.get(key, "") for key in ("PORTAL", "MAC", "M3U", "XT_URL", "XT_USER", "XT_PASS")}
    missing = [key for key in ("PORTAL", "MAC", "M3U", "XT_URL") if not env[key]]
    if missing:
        sys.stderr.write("missing " + ", ".join(missing) + "\n")
        sys.exit(1)
    return env


def mask(text, mac, token):
    if mac:
        text = re.sub(re.escape(mac), "***", text, flags=re.I)
    if token:
        text = text.replace(token, "***")
    return text


def dry_run(env):
    token = os.environ.get("TOKEN", "")
    if not token:
        sys.stderr.write("missing TOKEN\n")
        sys.exit(1)
    # shortcut: dry-run shows a fresh store (isEnabled true). A real run keeps an existing off switch.
    values, _ = merge({}, env)
    for key in KEYS:
        sys.stdout.write(mask(f"{key}={values[key]}", env["MAC"], token) + "\n")


def fixture_env():
    return {
        "PORTAL": "http://h/stalker_portal/server/load.php",
        "MAC": "00:1a:79:aa:bb:cc",
        "M3U": "https://x/t/playlist.m3u?a=1&b=2",
        "XT_URL": "https://x/t",
        "XT_USER": "test",
        "XT_PASS": "test",
    }


def self_test():
    env = dict(fixture_env())
    env["M3U"] = "https://x/secret-token/playlist.m3u?a=1&b=2"
    env["XT_URL"] = "https://x/secret-token"
    other = {"id": "p1", "name": "Mine", "type": "Url", "source": "https://other/list.m3u", "isEnabled": True}
    existing = {
        "playlists_blob": dumps([other, playlist("https://old", False)]),
        "stalker_settings": stalker_json("http://old", "00:00:00:00:00:00", False),
        "xtream_settings": xtream_json("https://old", "test", "test", False),
        "favorite_channel_ids_blob": '["b","a"]',
    }
    merged, changed = merge(existing, env)
    assert changed
    assert '"isEnabled":false' in merged["stalker_settings"]
    assert "00:1A:79:AA:BB:CC" in merged["stalker_settings"]
    playlists = json.loads(merged["playlists_blob"])
    assert playlists[0]["source"] == "https://other/list.m3u"
    assert playlists[1]["isEnabled"] is False and playlists[1]["source"].endswith("a=1&b=2")
    assert '"isEnabled":false' in merged["xtream_settings"]
    assert merged["favorite_channel_ids_blob"] == '["b","a"]'
    blob = encode_prefs(merged)
    assert parse_prefs(blob) == merged
    _, again = merge(parse_prefs(blob), env)
    assert not again
    fresh, _ = merge({}, env)
    masked = mask("\n".join(f"{key}={fresh[key]}" for key in KEYS), env["MAC"], "secret-token")
    assert "secret-token" not in masked and "aa:bb:cc" not in masked.lower()
    assert "***" in masked and "playlist.m3u" in masked
    try:
        parse_prefs(b"\x00")
        raise AssertionError("garbage parsed")
    except ValueError:
        pass
    assert parse_prefs(b"") == {}
    print("self-test ok")


def main(argv):
    if argv == ["--self-test"]:
        self_test()
        return 0
    if argv == ["--fixture-pb"]:
        values, _ = merge({}, fixture_env())
        sys.stdout.buffer.write(encode_prefs(values))
        return 0
    if argv == ["--dry-run"]:
        dry_run(env_from_os())
        return 0
    if argv:
        sys.stderr.write("usage: livetv-bootstrap.py [--dry-run|--self-test|--fixture-pb]\n")
        return 2
    try:
        existing = parse_prefs(sys.stdin.buffer.read())
    except ValueError:
        sys.stderr.write("could not read the Live TV DataStore file\n")
        return 1
    values, changed = merge(existing, env_from_os())
    if not changed:
        return 3
    sys.stdout.buffer.write(encode_prefs(values))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
