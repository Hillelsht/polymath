#!/usr/bin/env python3
"""Build the Watch catalog by discovering real uploads from allowlisted channels.

An earlier version of this pipeline verified a hand-written list of video ids. That failed
badly — of 187 authored ids only 30 existed, and 26 of those pointed at unrelated videos —
because nobody can reliably write 11-character YouTube ids from memory. The lesson is baked
into the design here: **ids are never authored, only discovered.**

The allowlist is now a list of *channels*. For each one the pipeline resolves the handle to a
channel id, reads that channel's public RSS feed for its recent uploads, and takes the video
ids straight from YouTube. Every id is therefore real by construction, and every video comes
from a channel a human chose. It also self-maintains: new uploads appear on the next run
without anyone editing a file.

Keyless throughout — RSS and the public watch page only, no API key. Stdlib only.
"""

import html
import json
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANNELS_FILE = ROOT / "app" / "src" / "main" / "assets" / "content" / "channels.json"
CONTENT_DIR = ROOT / "app" / "src" / "main" / "assets" / "content"
PACKS_DIR = ROOT / "packs"
ASSETS_PACKS_DIR = ROOT / "app" / "src" / "main" / "assets" / "packs"

# A browser-ish agent: YouTube serves a stripped page to unknown clients.
UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0 Safari/537.36"
)
RSS = "https://www.youtube.com/feeds/videos.xml?channel_id="
ATOM = "{http://www.w3.org/2005/Atom}"
YT = "{http://www.youtube.com/xml/schemas/2015}"
MEDIA = "{http://search.yahoo.com/mrss/}"

# Shorts are not teaching material; anything under this is skipped.
MIN_SECONDS = 120
MIN_CHANNELS = 12
MIN_VIDEOS = 60

STOPWORDS = {
    "what", "why", "how", "the", "and", "for", "with", "from", "that", "this", "your", "you",
    "are", "was", "were", "his", "her", "its", "into", "about", "explained", "history",
    "science", "world", "does", "did", "can", "all", "one", "two", "new", "not", "but",
    "video", "part", "full", "documentary", "episode", "official", "everything",
}


def fetch(url, timeout=25):
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8", "replace")
    except Exception as e:
        return None


def resolve_channel_id(handle):
    """A handle is stable and memorable; the UC id is neither. Resolve one to the other."""
    page = fetch(f"https://www.youtube.com/@{handle}/videos")
    if not page:
        return None
    match = re.search(r'"(?:channelId|externalId)"\s*:\s*"(UC[\w-]{22})"', page)
    return match.group(1) if match else None


def channel_uploads(channel_id):
    """Recent uploads straight from YouTube's public feed — ids here are real by definition."""
    feed = fetch(RSS + channel_id)
    if not feed:
        return []
    try:
        root = ET.fromstring(feed)
    except ET.ParseError:
        return []

    videos = []
    for entry in root.findall(f"{ATOM}entry"):
        video_id = entry.findtext(f"{YT}videoId")
        title = entry.findtext(f"{ATOM}title")
        if not video_id or not title:
            continue
        group = entry.find(f"{MEDIA}group")
        thumb = None
        if group is not None:
            node = group.find(f"{MEDIA}thumbnail")
            if node is not None:
                thumb = node.get("url")
        videos.append(
            {
                "youtubeId": video_id,
                "title": html.unescape(title).strip(),
                "thumbnailUrl": thumb or f"https://img.youtube.com/vi/{video_id}/hqdefault.jpg",
            }
        )
    return videos


def duration_seconds(video_id):
    """Best effort: the watch page carries lengthSeconds. Unknown length is not fatal."""
    page = fetch(f"https://www.youtube.com/watch?v={video_id}", timeout=20)
    if not page:
        return None
    match = re.search(r'"lengthSeconds"\s*:\s*"(\d+)"', page)
    return int(match.group(1)) if match else None


def load_facts():
    facts = []
    for path in sorted(CONTENT_DIR.glob("*.json")):
        if path.name in {"videos.json", "channels.json"}:
            continue
        data = json.loads(path.read_text())
        for fact in data.get("facts", []):
            facts.append((data["category"], fact))
    return facts


def tokens(text):
    return {
        w
        for w in re.findall(r"[a-z]{4,}", text.lower())
        if w not in STOPWORDS
    }


def link_facts(title, category, facts_by_category):
    """Match a video title against fact titles and answers within the same subject.

    Deliberately conservative: two overlapping significant words before a link is claimed, so
    "Quiz me on this" asks about what the video actually covered rather than something loosely
    adjacent. Videos that match nothing simply do not offer the button.
    """
    title_tokens = tokens(title)
    if not title_tokens:
        return []

    scored = []
    for fact in facts_by_category.get(category, []):
        target = tokens(f"{fact['title']} {fact['answer']} {fact.get('wikiTitle', '')}")
        overlap = len(title_tokens & target)
        if overlap >= 2:
            scored.append((overlap, fact["id"]))
    scored.sort(reverse=True)
    return [fact_id for _, fact_id in scored[:4]]


def main():
    allowlist = json.loads(CHANNELS_FILE.read_text())["channels"]
    facts = load_facts()
    facts_by_category = {}
    for category, fact in facts:
        facts_by_category.setdefault(category, []).append(fact)

    videos = []
    resolved_channels = 0
    unresolved = []

    for entry in allowlist:
        handle, category = entry["handle"], entry["category"]
        channel_id = resolve_channel_id(handle)
        if not channel_id:
            unresolved.append(handle)
            print(f"[{category}] @{handle}: could not resolve — skipped")
            time.sleep(0.5)
            continue

        uploads = channel_uploads(channel_id)
        if not uploads:
            unresolved.append(handle)
            print(f"[{category}] @{handle}: no feed entries — skipped")
            time.sleep(0.5)
            continue

        resolved_channels += 1
        kept = 0
        for upload in uploads:
            seconds = duration_seconds(upload["youtubeId"])
            time.sleep(0.25)
            if seconds is not None and seconds < MIN_SECONDS:
                continue  # a Short, not a lesson
            minutes = round(seconds / 60) if seconds else None
            videos.append(
                {
                    "id": f"vid-{upload['youtubeId']}",
                    "youtubeId": upload["youtubeId"],
                    "category": category,
                    "title": upload["title"],
                    "channel": handle,
                    "minutes": minutes,
                    "thumbnailUrl": upload["thumbnailUrl"],
                    "relatedFactIds": link_facts(upload["title"], category, facts_by_category),
                }
            )
            kept += 1
        print(f"[{category}] @{handle}: {kept} videos")
        time.sleep(0.5)

    # De-duplicate: a channel can legitimately appear under one category only.
    seen, unique = set(), []
    for video in videos:
        if video["youtubeId"] in seen:
            continue
        seen.add(video["youtubeId"])
        unique.append(video)
    videos = unique

    per_category = {}
    for video in videos:
        per_category[video["category"]] = per_category.get(video["category"], 0) + 1
    linked = sum(1 for v in videos if v["relatedFactIds"])
    timed = sum(1 for v in videos if v["minutes"])

    print(f"\nChannels resolved: {resolved_channels}/{len(allowlist)}")
    if unresolved:
        print("Unresolved handles (fix or remove in channels.json):", ", ".join(unresolved))
    print(f"Videos: {len(videos)} | with duration: {timed} | linked to facts: {linked}")
    print("Per category:", ", ".join(f"{k}={v}" for k, v in sorted(per_category.items())))

    if resolved_channels < MIN_CHANNELS:
        print(f"FAIL: only {resolved_channels} channels resolved — YouTube may be blocking the runner")
        return 1
    if len(videos) < MIN_VIDEOS:
        print(f"FAIL: only {len(videos)} videos discovered")
        return 1

    missing = {"geography", "history", "science", "arts", "sports", "culture"} - per_category.keys()
    if missing:
        print(f"WARNING: no videos for {sorted(missing)} — those tabs will read as empty")

    catalog = {"packId": "videos", "videos": videos}
    body = json.dumps(catalog, ensure_ascii=False, indent=1)
    import hashlib

    catalog["version"] = hashlib.sha1(body.encode()).hexdigest()[:12]
    body = json.dumps(catalog, ensure_ascii=False, indent=1)

    PACKS_DIR.mkdir(exist_ok=True)
    ASSETS_PACKS_DIR.mkdir(parents=True, exist_ok=True)
    (PACKS_DIR / "videos.json").write_text(body)
    (ASSETS_PACKS_DIR / "videos.json").write_text(body)
    print(f"Wrote packs/videos.json ({len(body.encode()) // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
