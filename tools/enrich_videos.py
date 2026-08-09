#!/usr/bin/env python3
"""Probe the Watch tab's channel allowlist.

This script no longer builds a video list at all. The app discovers videos itself, live, from
each channel's public feed — so the only thing CI needs to publish is *which channels are
usable*, which is a few KB.

Two things have to be established per channel, and neither can be done on the device cheaply:

1. **The channel id.** Handles are what a human can write down; the stable UC… id is what the
   feed endpoint wants.
2. **Whether the channel allows embedding.** A feed says nothing about this, and a channel that
   forbids it produces YouTube error 152 — a card that looks fine and fails on tap. Sampling a
   few of its videos through oEmbed answers it once, for everyone, before shipping.

Keyless throughout. Stdlib only.
"""

import hashlib
import json
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALLOWLIST = ROOT / "app" / "src" / "main" / "assets" / "content" / "channels.json"
PACKS_DIR = ROOT / "packs"
ASSETS_PACKS_DIR = ROOT / "app" / "src" / "main" / "assets" / "packs"

UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0 Safari/537.36"
)
FEED = "https://www.youtube.com/feeds/videos.xml?channel_id="
OEMBED = "https://www.youtube.com/oembed"
ATOM = "{http://www.w3.org/2005/Atom}"
YT = "{http://www.youtube.com/xml/schemas/2015}"

SAMPLE_SIZE = 3
MIN_CHANNELS = 12


def fetch(url, timeout=25):
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept-Language": "en-US,en",
            # YouTube answers datacenter IPs with a consent interstitial without this.
            "Cookie": "CONSENT=YES+cb; SOCS=CAI",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8", "replace")
    except Exception:
        return None


def resolve_channel(handle):
    """Handle -> (channel id, display name)."""
    page = fetch(f"https://www.youtube.com/@{handle}/videos")
    if not page:
        return None, None
    match = re.search(r'"(?:channelId|externalId)"\s*:\s*"(UC[\w-]{22})"', page)
    if not match:
        return None, None
    name = re.search(r'"channelMetadataRenderer"\s*:\s*\{\s*"title"\s*:\s*"([^"]{1,80})"', page)
    return match.group(1), (name.group(1) if name else handle)


def sample_video_ids(channel_id, count=SAMPLE_SIZE):
    feed = fetch(FEED + channel_id)
    if not feed:
        return []
    try:
        root = ET.fromstring(feed)
    except ET.ParseError:
        return []
    ids = []
    for entry in root.findall(f"{ATOM}entry"):
        video_id = entry.findtext(f"{YT}videoId")
        if video_id:
            ids.append(video_id)
        if len(ids) >= count:
            break
    return ids


def embeddable(video_id):
    """oEmbed refuses videos whose owner disabled embedding — exactly the 152 case."""
    params = urllib.parse.urlencode(
        {"url": f"https://www.youtube.com/watch?v={video_id}", "format": "json"}
    )
    request = urllib.request.Request(f"{OEMBED}?{params}", headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            json.load(response)
            return True
    except Exception:
        return False


def main():
    allowlist = json.loads(ALLOWLIST.read_text())["channels"]

    usable, dropped = [], []
    for entry in allowlist:
        handle, category = entry["handle"], entry["category"]

        channel_id, display_name = resolve_channel(handle)
        if not channel_id:
            dropped.append(f"@{handle}: handle did not resolve")
            time.sleep(0.4)
            continue

        samples = sample_video_ids(channel_id)
        if not samples:
            dropped.append(f"@{handle}: feed empty")
            time.sleep(0.4)
            continue

        passes = sum(1 for vid in samples if embeddable(vid))
        time.sleep(0.4)

        if passes * 2 <= len(samples):  # needs a clear majority
            dropped.append(
                f"@{handle}: blocks embedding ({passes}/{len(samples)} playable) — "
                "would surface as error 152 on tap"
            )
            continue

        usable.append(
            {
                "id": channel_id,
                "handle": handle,
                "category": category,
                "displayName": display_name or handle,
            }
        )
        print(f"[{category}] @{handle} -> {channel_id} ({passes}/{len(samples)} embeddable)")

    print(f"\nUsable channels: {len(usable)}/{len(allowlist)}")
    if dropped:
        print("Dropped:")
        for line in dropped:
            print("  -", line)

    if len(usable) < MIN_CHANNELS:
        print(f"FAIL: only {len(usable)} channels usable — YouTube may be blocking the runner")
        return 1

    per_category = {}
    for channel in usable:
        per_category[channel["category"]] = per_category.get(channel["category"], 0) + 1
    print("Per category:", ", ".join(f"{k}={v}" for k, v in sorted(per_category.items())))

    missing = {"geography", "history", "science", "arts", "sports", "culture"} - per_category.keys()
    if missing:
        print(f"WARNING: no usable channels for {sorted(missing)} — those filters will be empty")

    payload = {"channels": usable}
    body = json.dumps(payload, ensure_ascii=False, indent=1)
    payload["version"] = hashlib.sha1(body.encode()).hexdigest()[:12]
    body = json.dumps(payload, ensure_ascii=False, indent=1)

    PACKS_DIR.mkdir(exist_ok=True)
    ASSETS_PACKS_DIR.mkdir(parents=True, exist_ok=True)
    (PACKS_DIR / "channels.json").write_text(body)
    (ASSETS_PACKS_DIR / "channels.json").write_text(body)

    # The old video catalogue is obsolete: the app builds its own shelf now.
    for stale in (PACKS_DIR / "videos.json", ASSETS_PACKS_DIR / "videos.json"):
        if stale.exists():
            stale.unlink()
            print(f"Removed stale {stale.relative_to(ROOT)}")

    print(f"Wrote packs/channels.json ({len(body.encode())} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
