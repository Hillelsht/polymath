#!/usr/bin/env python3
"""Verify and enrich the curated video catalog.

Every entry is checked against YouTube's keyless oEmbed endpoint. A video that no longer
exists, has been made private, or blocks embedding is dropped rather than shipped — a card
that opens onto an error is worse than no card. Surviving entries get YouTube's canonical
title, channel name and thumbnail, so the catalog cannot drift from reality.

Runs in CI (GitHub runners can reach YouTube). Stdlib only.
"""

import hashlib
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app" / "src" / "main" / "assets" / "content" / "videos.json"
PACKS_DIR = ROOT / "packs"
ASSETS_PACKS_DIR = ROOT / "app" / "src" / "main" / "assets" / "packs"

OEMBED = "https://www.youtube.com/oembed"
USER_AGENT = "SmartTriviaContentPipeline/3.0 (https://github.com/hillelsht/smart)"

# Below this share of surviving videos, assume the endpoint or network is broken rather than
# the catalog, and fail instead of quietly shipping a gutted Watch tab.
MIN_SURVIVAL = 0.70


def probe(youtube_id):
    """Returns oEmbed metadata for an embeddable video, or None."""
    params = urllib.parse.urlencode(
        {"url": f"https://www.youtube.com/watch?v={youtube_id}", "format": "json"}
    )
    request = urllib.request.Request(f"{OEMBED}?{params}", headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        # 404 = gone, 401/403 = private or embedding disabled. All mean "do not ship".
        return None
    except Exception:
        return None


def main():
    catalog = json.loads(SOURCE.read_text())
    videos = catalog["videos"]

    def similar(a, b):
        """Cheap word-overlap check — enough to flag an id pointing at the wrong subject."""
        wa = {w for w in a.lower().split() if len(w) > 3}
        wb = {w for w in b.lower().split() if len(w) > 3}
        if not wa or not wb:
            return 1.0
        return len(wa & wb) / min(len(wa), len(wb))

    kept, dropped, drifted = [], [], []
    for video in videos:
        info = probe(video["youtubeId"])
        if info is None:
            dropped.append(f"{video['id']} ({video['youtubeId']}): unavailable or not embeddable")
            time.sleep(0.2)
            continue

        # Trust YouTube over the authoring file for anything YouTube actually knows.
        canonical = info.get("title") or video["title"]
        # oEmbed proves a video exists; it cannot prove the video is about the claimed topic.
        # A canonical title sharing almost no words with the authored one is the one signal
        # available that an id points somewhere unintended — surface it loudly for review.
        if similar(canonical, video["title"]) < 0.34:
            drifted.append(
                f"{video['id']}: authored \"{video['title']}\" -> actual \"{canonical}\""
            )
        video["title"] = canonical
        video["channel"] = info.get("author_name") or video["channel"]
        video["thumbnailUrl"] = (
            info.get("thumbnail_url") or f"https://img.youtube.com/vi/{video['youtubeId']}/hqdefault.jpg"
        )
        kept.append(video)
        time.sleep(0.2)

    total = len(videos)
    print(f"\nVerified {len(kept)}/{total} videos")
    if dropped:
        print(f"{len(dropped)} dropped:")
        for d in dropped:
            print("  -", d)
    if drifted:
        print(f"\n{len(drifted)} TITLE MISMATCHES — check these point at the intended video:")
        for d in drifted:
            print("  ?", d)

    if total and len(kept) / total < MIN_SURVIVAL:
        print(f"FAIL: only {len(kept)}/{total} survived — refusing to ship a gutted catalog")
        return 1

    by_category = {}
    for video in kept:
        by_category[video["category"]] = by_category.get(video["category"], 0) + 1
    print("Per category:", ", ".join(f"{k}={v}" for k, v in sorted(by_category.items())))

    if len(by_category) < 6:
        missing = {"geography", "history", "science", "arts", "sports", "culture"} - by_category.keys()
        print(f"FAIL: no surviving videos for {sorted(missing)}")
        return 1

    enriched = {"packId": "videos", "videos": kept}
    body = json.dumps(enriched, ensure_ascii=False, indent=1)
    enriched["version"] = hashlib.sha1(body.encode()).hexdigest()[:12]
    body = json.dumps(enriched, ensure_ascii=False, indent=1)

    PACKS_DIR.mkdir(exist_ok=True)
    ASSETS_PACKS_DIR.mkdir(parents=True, exist_ok=True)
    (PACKS_DIR / "videos.json").write_text(body)
    (ASSETS_PACKS_DIR / "videos.json").write_text(body)
    print(f"Wrote packs/videos.json ({len(body.encode()) // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
