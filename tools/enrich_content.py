#!/usr/bin/env python3
"""Enrich the authoring content into shippable packs.

Reads the hand-written fact files in content/, resolves every fact's wikiTitle against the
Wikipedia Action API — direct thumbnail URL, canonical page URL, and a ~10-sentence extract —
and writes enriched packs to packs/ plus a manifest, then mirrors the packs into the app's
bundled assets so a fresh install is enriched too.

Runs in CI (GitHub runners can reach Wikipedia). Stdlib only.
"""

import hashlib
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# Authoring source stays inside the app's assets so a build with no packs still has content.
CONTENT_DIR = ROOT / "app" / "src" / "main" / "assets" / "content"
PACKS_DIR = ROOT / "packs"
ASSETS_PACKS_DIR = ROOT / "app" / "src" / "main" / "assets" / "packs"

API = "https://en.wikipedia.org/w/api.php"
USER_AGENT = "SmartTriviaContentPipeline/2.0 (https://github.com/hillelsht/smart)"

# The Action API caps prop=extracts at 20 pages per request; batch to that.
BATCH = 20
THUMB_SIZE = 640
SENTENCES = 10

# Files in the content directory that are not fact packs.
NON_FACT_FILES = {"videos.json", "channels.json"}

PACK_NAMES = {
    "geography": "Geography",
    "history": "History",
    "science": "Science",
    "arts": "Arts & Literature",
    "sports": "Sports & Games",
    "culture": "Pop Culture",
}


def api_query(titles):
    """One batched query, following the API's continuation cursor.

    TextExtracts hands back extracts for only a page or two per response and expects the
    client to keep requesting with the returned `continue` parameters until done — without
    this loop most facts silently get no extract. `pilicense=any` includes non-free page
    images (film posters, album covers), which are exactly the pictures pop-culture facts
    need; the default free-only setting drops them.
    """
    base = {
        "action": "query",
        "format": "json",
        "formatversion": "2",
        "redirects": "1",
        "prop": "pageimages|extracts|info",
        "pithumbsize": str(THUMB_SIZE),
        "pilicense": "any",
        "exsentences": str(SENTENCES),
        "explaintext": "1",
        "exlimit": "max",
        "inprop": "url",
        "titles": "|".join(titles),
    }

    merged = {"normalized": [], "redirects": [], "pages": {}}
    cont = {}
    for _ in range(40):  # hard stop; a batch never legitimately needs this many rounds
        params = dict(base)
        params.update(cont)
        url = API + "?" + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.load(resp)

        query = data.get("query", {})
        merged["normalized"] += query.get("normalized", [])
        merged["redirects"] += query.get("redirects", [])
        for page in query.get("pages", []):
            key = page.get("pageid") or page.get("title")
            existing = merged["pages"].setdefault(key, {})
            # Later continuation rounds fill in fields the first round lacked.
            for field, value in page.items():
                existing.setdefault(field, value)

        if "continue" in data:
            cont = data["continue"]
            time.sleep(0.2)
        else:
            break

    return {"query": {
        "normalized": merged["normalized"],
        "redirects": merged["redirects"],
        "pages": list(merged["pages"].values()),
    }}


def resolve_titles(titles):
    """Maps each requested title to {imageUrl, pageUrl, details}."""
    out = {}
    for i in range(0, len(titles), BATCH):
        chunk = titles[i : i + BATCH]
        data = api_query(chunk)
        query = data.get("query", {})

        # The API normalises and follows redirects; map responses back to what we asked for.
        back = {}
        for m in query.get("normalized", []) + query.get("redirects", []):
            back[m["to"]] = back.get(m["from"], m["from"])
            # chase chains: requested -> normalised -> redirected
            for k, v in list(back.items()):
                if v in back and back[v] != v:
                    back[k] = back[v]

        for page in query.get("pages", []):
            final_title = page.get("title", "")
            requested = back.get(final_title, final_title)
            if page.get("missing"):
                out[requested] = None
                continue
            out[requested] = {
                "imageUrl": page.get("thumbnail", {}).get("source"),
                "pageUrl": page.get("fullurl"),
                "details": (page.get("extract") or "").strip() or None,
            }
        time.sleep(0.4)
    return out


def validate(all_facts):
    errors = []
    ids = {}
    questions = {}
    by_type = {}
    for fact in all_facts:
        fid = fact["id"]
        if fid in ids:
            errors.append(f"duplicate id {fid}")
        ids[fid] = True
        q = fact["question"].strip().lower()
        if q in questions:
            errors.append(f"duplicate question in {fid} and {questions[q]}")
        questions[q] = fid
        by_type.setdefault(fact["answerType"], set()).add(fact["answer"].strip().lower())
    for atype, answers in sorted(by_type.items()):
        if len(answers) < 4:
            errors.append(f"answerType '{atype}' has only {len(answers)} distinct answers (<4)")
    return errors


def main():
    PACKS_DIR.mkdir(exist_ok=True)
    ASSETS_PACKS_DIR.mkdir(parents=True, exist_ok=True)

    manifest = []
    all_facts = []
    total = images = extracts = 0
    missing_images = []

    # The content directory also holds the Watch tab's files, which are a different schema
    # entirely and belong to tools/enrich_videos.py.
    for src in sorted(f for f in CONTENT_DIR.glob("*.json") if f.name not in NON_FACT_FILES):
        data = json.loads(src.read_text())
        category = data["category"]
        facts = data["facts"]
        all_facts.extend(facts)

        titles = sorted({f["wikiTitle"] for f in facts if f.get("wikiTitle")})
        print(f"[{category}] resolving {len(titles)} titles for {len(facts)} facts")
        resolved = resolve_titles(titles)

        for fact in facts:
            total += 1
            info = resolved.get(fact.get("wikiTitle") or "")
            if not info:
                missing_images.append(f"{fact['id']} ({fact.get('wikiTitle')}): page missing")
                continue
            if info["imageUrl"]:
                fact["imageUrl"] = info["imageUrl"]
                images += 1
            else:
                missing_images.append(f"{fact['id']} ({fact.get('wikiTitle')}): no page image")
            if info["pageUrl"]:
                fact["pageUrl"] = info["pageUrl"]
            if info["details"]:
                fact["details"] = info["details"]
                extracts += 1

        enriched = {
            "category": category,
            "packId": category,
            "name": PACK_NAMES.get(category, category.title()),
            "facts": facts,
        }
        body = json.dumps(enriched, ensure_ascii=False, indent=1)
        version = hashlib.sha1(body.encode()).hexdigest()[:12]
        enriched["version"] = version
        body = json.dumps(enriched, ensure_ascii=False, indent=1)

        out = PACKS_DIR / f"{category}.json"
        out.write_text(body)
        (ASSETS_PACKS_DIR / f"{category}.json").write_text(body)
        manifest.append(
            {
                "id": category,
                "name": enriched["name"],
                "category": category,
                "version": version,
                "facts": len(facts),
                "bytes": len(body.encode()),
                "file": f"{category}.json",
            }
        )

    errors = validate(all_facts)
    if errors:
        print("VALIDATION ERRORS:")
        for e in errors:
            print("  -", e)
        return 1

    (PACKS_DIR / "manifest.json").write_text(
        json.dumps(
            {"generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "packs": manifest},
            ensure_ascii=False,
            indent=1,
        )
    )

    print(f"\nEnriched {total} facts: {images} images, {extracts} extracts")
    if missing_images:
        print(f"{len(missing_images)} facts without an image (gradient fallback on device):")
        for m in missing_images:
            print("  -", m)
    # Images are best-effort; a missing one degrades gracefully. But losing most of them
    # means the API broke — fail the run so it can't silently ship a pictureless release.
    if images < total * 0.7:
        print(f"FAIL: only {images}/{total} images resolved")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
