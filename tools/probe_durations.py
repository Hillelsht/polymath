#!/usr/bin/env python3
"""Find a keyless source of video durations, or prove there isn't one.

The Watch tab's length filter only worked on videos already opened, because the only thing
reporting a duration was the player itself. Fixing that needs durations for the whole shelf,
which has to come from CI. Before asking anyone for a YouTube Data API key, check whether the
answer is already free:

1. The channel RSS feed the app *already downloads*. If a duration is in there, the problem
   disappears entirely and the device can do it alone.
2. youtube.com/embed/<id>. CI reaches oEmbed happily — the channel prober depends on it — while
   *watch* pages are blocked from the runner (the v4 discovery run got 0 durations from 455
   videos). The embed page is a different endpoint and carries lengthSeconds. Untested.

Prints a verdict. Nothing is assumed; every claim below is something this script observed.
"""

import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0 Safari/537.36")
FEED = "https://www.youtube.com/feeds/videos.xml?channel_id="
ATOM = "{http://www.w3.org/2005/Atom}"
YT = "{http://www.youtube.com/xml/schemas/2015}"
MEDIA = "{http://search.yahoo.com/mrss/}"

SAMPLE_CHANNEL = "UCYO_jab_esuFRV4b17AJtAw"   # 3Blue1Brown


def fetch(url, timeout=25):
    request = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept-Language": "en-US,en",
        "Cookie": "CONSENT=YES+cb; SOCS=CAI",
    })
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8", "replace"), response.status
    except Exception as exc:
        return None, getattr(exc, "code", type(exc).__name__)


def probe_feed():
    """Does the feed the app already fetches carry a duration anywhere?"""
    body, status = fetch(FEED + SAMPLE_CHANNEL)
    if not body:
        return None, f"feed unreachable ({status})"

    root = ET.fromstring(body)
    entry = root.find(f"{ATOM}entry")
    if entry is None:
        return None, "feed had no entries"

    # Dump every tag and attribute inside one entry, then look for anything duration-shaped.
    seen = []
    for node in entry.iter():
        tag = node.tag.split("}")[-1]
        seen.append(tag)
        for name, value in node.attrib.items():
            if "dur" in name.lower() or "length" in name.lower():
                return int(float(value)), f"feed carries {tag}@{name}={value}"
    hit = re.search(r'(?:duration|lengthSeconds)["\s:=]+(\d+)', body, re.I)
    if hit:
        return int(hit.group(1)), f"feed carries a duration-like field: {hit.group(0)[:40]}"
    return None, f"no duration in the feed; entry contains {sorted(set(seen))}"


def probe_embed(video_id):
    """Is the embed page reachable from a runner, and does it expose lengthSeconds?"""
    body, status = fetch(f"https://www.youtube.com/embed/{video_id}")
    if not body:
        return None, f"embed page unreachable ({status})"
    hit = re.search(r'\\?"lengthSeconds\\?":\\?"(\d+)', body)
    if hit:
        return int(hit.group(1)), f"embed page exposes lengthSeconds ({len(body)//1024} KB)"
    return None, f"embed page reachable ({len(body)//1024} KB) but no lengthSeconds in it"


def sample_ids(count=3):
    body, _ = fetch(FEED + SAMPLE_CHANNEL)
    if not body:
        return []
    root = ET.fromstring(body)
    return [e.findtext(f"{YT}videoId") for e in root.findall(f"{ATOM}entry")][:count]


def main():
    print("Looking for a keyless duration source.\n")

    seconds, note = probe_feed()
    print(f"1. channel RSS feed  -> {note}")
    if seconds:
        print(f"\nVERDICT: the feed carries durations ({seconds}s). No key, no extra request,")
        print("         the device can do this entirely on its own.")
        return 0

    ids = sample_ids()
    if not ids:
        print("\nCould not sample video ids; YouTube may be blocking this runner entirely.")
        return 2

    print(f"\n2. embed page        -> sampling {len(ids)} videos")
    found = {}
    for video_id in ids:
        seconds, note = probe_embed(video_id)
        print(f"     {video_id}: {note}")
        if seconds:
            found[video_id] = seconds

    if len(found) == len(ids):
        print(f"\nVERDICT: the embed page works from CI. {found}")
        print("         Build the duration map keylessly from these.")
        return 0
    if found:
        print(f"\nVERDICT: embed page worked for {len(found)}/{len(ids)} — unreliable.")
        return 1

    print("\nVERDICT: no keyless source. A YouTube Data API key is required,")
    print("         held as a CI secret and never shipped in the APK.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
