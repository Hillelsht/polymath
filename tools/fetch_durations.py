#!/usr/bin/env python3
"""Publish a duration for every video the Watch shelf can show.

Why this exists: the app's length filter could only ever work on videos you had already opened,
because the IFrame player is the only thing that reports a runtime. A probe (tools/probe_
durations.py) established that neither the channel RSS nor the embed page carries one, so the
YouTube Data API is the only source.

Why it runs in CI rather than on the phone: the device would need ~540 lookups per refresh.
Here it is 11 API calls, once, for every install. The key is a repository secret and never
reaches the APK.

Quota: videos.list?part=contentDetails costs 1 unit per call, 50 ids per call. 36 channels x 15
recent uploads is ~540 ids => 11 units per run. The free allowance is 10,000 units a day, so
running hourly spends about 0.3% of it.
"""

import json
import os
import re
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKS = ROOT / "packs"
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "packs"

FEED = "https://www.youtube.com/feeds/videos.xml?channel_id="
API = "https://www.googleapis.com/youtube/v3/videos"
ATOM = "{http://www.w3.org/2005/Atom}"
YT = "{http://www.youtube.com/xml/schemas/2015}"
UA = "SmartTriviaPipeline/1.0 (+https://github.com/hillelsht/smart)"
BATCH = 50

ISO = re.compile(
    r"^P(?:(?P<days>\d+)D)?T(?:(?P<hours>\d+)H)?(?:(?P<minutes>\d+)M)?(?:(?P<seconds>\d+)S)?$"
)


def iso_to_seconds(value):
    """YouTube returns ISO 8601 durations: PT4M13S, PT1H2M3S, PT59S, and rarely P1DT2H."""
    match = ISO.match(value or "")
    if not match:
        return None
    parts = {k: int(v or 0) for k, v in match.groupdict().items()}
    total = parts["days"] * 86400 + parts["hours"] * 3600 + parts["minutes"] * 60 + parts["seconds"]
    return total or None


def _self_test():
    """Runs on every invocation. A silently wrong parser would mislabel the whole shelf."""
    cases = {
        "PT4M13S": 253, "PT1H2M3S": 3723, "PT59S": 59, "PT15M": 900,
        "PT2H": 7200, "P1DT2H": 93600, "PT0S": None, "": None, "banana": None,
    }
    for text, expected in cases.items():
        actual = iso_to_seconds(text)
        assert actual == expected, f"iso_to_seconds({text!r}) = {actual}, expected {expected}"


def fetch(url, timeout=30):
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8", "replace")
    except Exception as exc:
        print(f"  ! {type(exc).__name__} {getattr(exc, 'code', '')} for {url.split('?')[0]}")
        return None


def recent_video_ids(channel_id):
    body = fetch(FEED + channel_id)
    if not body:
        return []
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return []
    return [e.findtext(f"{YT}videoId") for e in root.findall(f"{ATOM}entry") if e is not None]


def durations_for(ids, key):
    """One call per 50 ids. Returns {videoId: seconds}."""
    found = {}
    for start in range(0, len(ids), BATCH):
        chunk = [i for i in ids[start:start + BATCH] if i]
        if not chunk:
            continue
        query = urllib.parse.urlencode(
            {"part": "contentDetails", "id": ",".join(chunk), "key": key, "maxResults": BATCH}
        )
        body = fetch(f"{API}?{query}")
        if not body:
            continue
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            continue
        if "error" in payload:
            reason = payload["error"].get("message", "unknown")
            print(f"  ! API refused the request: {reason}")
            return found, False
        for item in payload.get("items", []):
            seconds = iso_to_seconds(item.get("contentDetails", {}).get("duration"))
            if seconds:
                found[item["id"]] = seconds
    return found, True


def main():
    _self_test()

    key = os.environ.get("YOUTUBE_API_KEY", "").strip()
    if not key:
        # Deliberately not an error. Until the secret exists the app simply has no durations,
        # and the length filter stays hidden — which is the behaviour it already ships with.
        print("No YOUTUBE_API_KEY set; skipping. The length filter stays hidden until there is one.")
        return 0

    allowlist = PACKS / "channels.json"
    if not allowlist.exists():
        print("packs/channels.json missing — run the channel prober first.")
        return 1
    channels = json.loads(allowlist.read_text())["channels"]

    ids = []
    for channel in channels:
        ids.extend(recent_video_ids(channel["id"]))
    ids = list(dict.fromkeys(i for i in ids if i))
    print(f"{len(channels)} channels -> {len(ids)} recent uploads")

    found, ok = durations_for(ids, key)
    calls = (len(ids) + BATCH - 1) // BATCH
    print(f"{len(found)}/{len(ids)} durations resolved in ~{calls} API calls ({calls} quota units)")

    if not ok:
        print("FAIL: the API rejected the key. Leaving any existing map untouched.")
        return 1
    if len(found) < len(ids) * 0.5:
        # A partial answer would make the shelf look half-measured and the filter erratic.
        print(f"FAIL: only {len(found)} of {len(ids)} resolved. Leaving the existing map alone.")
        return 1

    body = json.dumps({"durations": found}, sort_keys=True, separators=(",", ":"))
    PACKS.mkdir(exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    (PACKS / "durations.json").write_text(body)
    (ASSETS / "durations.json").write_text(body)
    print(f"Wrote packs/durations.json ({len(body) // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
