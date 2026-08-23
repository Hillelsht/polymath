#!/usr/bin/env python3
"""The catalogue: what a device can see, as opposed to what the repository happens to contain.

A pack in `packs/` is not a pack anybody has. `PackService` reads exactly two files to discover
content — `manifest.json` for the catalogue somebody chooses from, and `library/index.json` for
the shards that top themselves up — and a file listed in neither is bytes on a CDN that nothing
will ever request. `docs/invariants.md` records the bundled half of this rule; this is the
published half, and it had two live breaches when this file was written.

**Topic packs had nowhere to be listed.** `topic_pack.py` writes to `packs/community/`, and the
manifest was rebuilt by `enrich_content.py` from `content/*.json` alone — so a generated topic pack
was not merely unlisted, it was *unlistable*: adding it by hand would survive until the next
content run and then vanish, which is the worst version of this bug because it works when you test
it.

**Russian and Hebrew had no catalogue at all.** `packs/ru/geography.json` and its Hebrew twin have
been published for weeks, and `fetchManifest(ru)` asks for `packs/ru/manifest.json`, which has
never existed. It 404s, the catalogue comes back null, and the Library tab in those languages
offers nothing to download — while the file sits there, served, one URL away. Nothing failed
loudly enough to notice, because null is also what "CI has not published yet" looks like.

Both are the same missing step, so this is one tool: read every fact pack actually published, in
every language, and write the catalogue that names them.

It also stamps a `version` into any pack lacking one. `ContentParser` falls back to hashing the
raw text when a pack does not name its version, which works but means the manifest cannot state a
version the device will agree with — and a catalogue that permanently disagrees with what is
installed is a catalogue that permanently claims an update is available. Stamping costs one
re-seed of the affected pack, with its fact ids unchanged, so review history is untouched.

    python3 tools/build_manifest.py                # write every manifest
    python3 tools/build_manifest.py --check        # fail if any is stale (this is what CI runs)
    python3 tools/build_manifest.py --self-test
"""

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKS = ROOT / "packs"

# Every language the app ships, English first. Kept as a literal rather than imported from the
# Kotlin enum: this tool runs where there is no JVM, and a tag added here without a matching
# `Language` entry writes a manifest nothing fetches, which `--check` cannot see either.
LANGUAGES = ["en", "ru", "he"]
DEFAULT_LANGUAGE = "en"

# Directories under a language root that hold packs. `library/` is deliberately absent: its shards
# are a *supply* consumed automatically through `library/index.json`, not a catalogue anybody
# chooses from, and listing three thousand of them here would bury the six that are choices.
PACK_DIRS = ["", "community"]

MANIFEST = "manifest.json"

# Where the APK's own copy of a curated pack lives. Stamping a version into a published pack
# without stamping its bundled twin leaves the two disagreeing, and the device then downloads a
# pack it already has on every refresh — the same disagreement the stamp exists to end, moved one
# file along. Only packs that already have a twin are mirrored: a topic pack is downloaded, and
# copying it in here would grow the APK by exactly the content that was never supposed to be in it.
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "packs"


def shown(path):
    """A path as a reader recognises it. The self-test builds a tree in /tmp, which is not under
    the repository root, so `relative_to` alone turns every message into a traceback."""
    try:
        return Path(path).relative_to(ROOT).as_posix()
    except ValueError:
        return Path(path).name


def is_pack(data):
    """Whether a JSON file is a fact pack, by the same test `validate_pack.py` uses.

    `packs/` also holds the Watch allowlist, the video durations, the daily grids and the
    manifests themselves. None of them is a pack and all of them are JSON.
    """
    return isinstance(data, dict) and "category" in data and "facts" in data


def versioned(data):
    """The pack's data with a version guaranteed, computed the way `enrich_content.py` computes it.

    In memory only. Reading and writing are split because `--check` must be able to *report* an
    unversioned pack without quietly fixing it — an earlier version stamped during the check, so
    the check passed by having already done the thing it was meant to be checking for.
    """
    if data.get("version"):
        return data, False
    body = json.dumps({k: v for k, v in data.items() if k != "version"},
                      ensure_ascii=False, indent=1)
    return {**data, "version": hashlib.sha1(body.encode("utf-8")).hexdigest()[:12]}, True


def stamp(path, packs_dir=PACKS, assets=None):
    """Writes a version into a pack that names none. Returns whether it had to.

    A pack without one is not a cosmetic gap. `ContentParser` falls back to hashing the raw text,
    which no tool outside the app can reproduce, so the catalogue and the device would disagree
    about the version forever — and a device that thinks its installed pack is stale offers the
    update again on every refresh, downloading the same bytes for as long as the app is installed.
    """
    raw = path.read_text(encoding="utf-8")
    data = json.loads(raw)
    data, missing = versioned(data)
    if not missing:
        return False
    # Keep whatever the file already ended with. A stamp should be a one-line diff; adding or
    # dropping a trailing newline turns it into a whole-file rewrite in every reviewer's diff.
    body = json.dumps(data, ensure_ascii=False, indent=1) + ("\n" if raw.endswith("\n") else "")
    path.write_text(body, encoding="utf-8")
    twin = mirror_of(path, packs_dir, assets)
    if twin is not None and twin.is_file():
        twin.write_text(body, encoding="utf-8")
        print(f"  stamped {shown(path)} v{data['version']} (and its bundled twin)")
    else:
        print(f"  stamped {shown(path)} v{data['version']}")
    return True


def mirror_of(path, packs_dir=PACKS, assets=None):
    """The APK's copy of a published pack, or None if there is no coherent place for one.

    [assets] of None means "derive it", and derives it only for the real `packs/`. That is not
    caution for its own sake: the self-test builds a pack tree in a temporary directory, and an
    earlier version defaulted this to the repository's own assets folder — so `stamp` mapped
    `/tmp/…/packs/ru/geography.json` onto the *real* `assets/packs/ru/geography.json` and
    overwrote the bundled Russian pack with a three-fact fixture. `RussianPackTest` caught it,
    which is the only reason it is a paragraph here rather than a shipped APK teaching ten facts
    that do not exist. A test that can write into the repository is a test that eventually will.
    """
    root = Path(packs_dir)
    if assets is None:
        if root.resolve() != PACKS.resolve():
            return None
        assets = ASSETS
    try:
        return Path(assets) / Path(path).relative_to(root)
    except ValueError:
        return None


def entry(path, data, root):
    """One catalogue row. `file` is relative to the language root, which is what the device joins."""
    return {
        "id": data.get("packId") or data["category"],
        "name": data.get("name") or data["category"].title(),
        "category": data["category"],
        "version": data["version"],
        "facts": len(data["facts"]),
        "bytes": len(path.read_text(encoding="utf-8").encode("utf-8")),
        "file": path.relative_to(root).as_posix(),
    }


def collect(language, packs_dir=PACKS, write_stamps=False, unversioned=None,
            assets=None):
    """Every pack published for one language, curated first and then everything else.

    Order is the shelf order. The six curated packs are the ones a person came for; a topic pack
    is something they went looking for, and putting it above Geography would be reordering the app
    around the newest thing rather than the most useful one.
    """
    root = packs_dir if language == DEFAULT_LANGUAGE else packs_dir / language
    if not root.is_dir():
        return []

    rows = []
    unversioned = [] if unversioned is None else unversioned
    for sub in PACK_DIRS:
        directory = root / sub if sub else root
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("*.json")):
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                continue
            if not is_pack(data):
                continue
            # A pack's declared language decides which catalogue it belongs in, not the folder it
            # sits in. They agree today; if one ever does not, the file is the one that would be
            # parsed on the device, so the file wins.
            if data.get("language", DEFAULT_LANGUAGE) != language:
                print(f"  {shown(path)} declares "
                      f"'{data.get('language', DEFAULT_LANGUAGE)}' under {language}/ — skipped")
                continue
            if write_stamps:
                stamp(path, packs_dir, assets)
            data, missing = versioned(data)
            if missing:
                unversioned.append(path)
            rows.append(entry(path, data, root))
    return rows


def manifest_for(language, packs_dir=PACKS, generated=None):
    rows = collect(language, packs_dir)
    return {
        "generated": generated or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "packs": rows,
    }


def body(manifest):
    return json.dumps(manifest, ensure_ascii=False, indent=1) + "\n"


def path_for(language, packs_dir=PACKS):
    root = packs_dir if language == DEFAULT_LANGUAGE else packs_dir / language
    return root / MANIFEST


def build(packs_dir=PACKS, languages=None, check=False, assets=None):
    """Writes (or checks) every language's catalogue. Returns 0, or 1 if `--check` found staleness.

    `--check` compares the *packs* rather than the timestamp, because the timestamp changes on
    every run and would make the check fail always, which is the same as not having it.
    """
    stale = []
    for language in languages or LANGUAGES:
        unversioned = []
        rows = collect(language, packs_dir, write_stamps=not check,
                       unversioned=unversioned, assets=assets)
        if unversioned and check:
            # Reported as its own failure rather than folded into "stale", because the manifest
            # can look perfectly current while every device re-downloads the pack forever.
            stale.append(language)
            for path in unversioned:
                print(f"  {language}: {shown(path)} names no version — "
                      "the device would never agree it is up to date")
        target = path_for(language, packs_dir)
        if not rows:
            if target.is_file():
                print(f"  {language}: no packs published, but a manifest exists — left alone")
            continue

        existing = None
        if target.is_file():
            try:
                existing = json.loads(target.read_text(encoding="utf-8")).get("packs")
            except json.JSONDecodeError:
                existing = None

        if existing == rows:
            print(f"  {language}: {len(rows)} packs, unchanged")
            continue
        if check:
            stale.append(language)
            print(f"  {language}: manifest is stale — {len(rows)} packs published, "
                  f"{len(existing or [])} listed")
            continue

        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body(manifest_for(language, packs_dir)), encoding="utf-8")
        print(f"  {language}: wrote {shown(target)} with {len(rows)} packs")

    if stale:
        named = sorted(set(stale))
        print(f"\n{', '.join(named)} out of date. Run: python3 tools/build_manifest.py")
        return 1
    return 0


# --- self-test ------------------------------------------------------------------------------

def self_test():
    import tempfile
    failures = []

    def check(name, condition):
        print(f"  {'ok  ' if condition else 'FAIL'} {name}")
        if not condition:
            failures.append(name)

    def pack(category="geography", pack_id=None, language="en", facts=3, version=None,
             name=None):
        data = {"category": category, "packId": pack_id or category,
                "name": name or category.title(), "language": language,
                "facts": [{"id": f"{pack_id or category}-{i}", "title": f"T{i}",
                           "question": f"Q{i}?", "answer": f"A{i}", "answerType": "thing",
                           "statement": f"A{i} is the answer to {i}."} for i in range(facts)]}
        if version:
            data["version"] = version
        return data

    with tempfile.TemporaryDirectory() as tmp:
        packs = Path(tmp) / "packs"
        (packs / "community").mkdir(parents=True)
        (packs / "ru" / "community").mkdir(parents=True)
        (packs / "library").mkdir()

        (packs / "geography.json").write_text(json.dumps(pack(version="abc123")))
        (packs / "community" / "topic-rivers-geography.json").write_text(
            json.dumps(pack("geography", "topic-rivers-geography", name="Rivers")))
        (packs / "ru" / "geography.json").write_text(
            json.dumps(pack("geography", "geography-ru", "ru")))
        (packs / "ru" / "community" / "topic-rivers-geography-ru.json").write_text(
            json.dumps(pack("geography", "topic-rivers-geography-ru", "ru")))

        # The three things in packs/ that are JSON and are not packs.
        (packs / "channels.json").write_text(json.dumps({"channels": []}))
        (packs / "durations.json").write_text(json.dumps({"durations": {}}))
        (packs / "library" / "library-geography-000.json").write_text(
            json.dumps(pack("geography", "library-geography-000")))

        rows = collect("en", packs)
        ids = [r["id"] for r in rows]
        check("the curated pack is catalogued", "geography" in ids)
        check("and so is the topic pack, which is the whole point",
              "topic-rivers-geography" in ids)
        check("curated comes first, because it is what somebody came for",
              ids.index("geography") < ids.index("topic-rivers-geography"))
        check("the Watch allowlist is not a pack", "channels" not in " ".join(ids))
        check("nor are the durations", len(rows) == 2)
        check("a library shard is a supply, not a catalogue entry",
              not any("library-" in i for i in ids))
        check("the file path is relative to the language root, which is what the device joins",
              {r["file"] for r in rows} ==
              {"geography.json", "community/topic-rivers-geography.json"})
        check("a row carries the counts the catalogue shows",
              all(r["facts"] == 3 and r["bytes"] > 0 for r in rows))

        ru = collect("ru", packs)
        check("Russian gets its own catalogue, which it has never had",
              {r["id"] for r in ru} == {"geography-ru", "topic-rivers-geography-ru"})
        check("and its paths do not repeat the language tag",
              all(not r["file"].startswith("ru/") for r in ru))

        stamped = json.loads((packs / "ru" / "geography.json").read_text())
        check("collect alone does not write a version, so --check cannot self-satisfy",
              "version" not in json.loads((packs / "ru" / "geography.json").read_text()))
        check("--check refuses a published pack that names no version",
              build(packs, check=True) == 1)
        build(packs)
        stamped = json.loads((packs / "ru" / "geography.json").read_text())
        check("building stamps a pack that named no version", bool(stamped.get("version")))
        check("and it is the same on a second run, so nothing re-seeds for the sake of it",
              json.loads((packs / "ru" / "geography.json").read_text())["version"]
              == stamped["version"] and collect("ru", packs)[0]["version"] == stamped["version"])
        check("a pack that already named a version keeps it exactly",
              json.loads((packs / "geography.json").read_text())["version"] == "abc123")

        check("Hebrew publishes nothing, so it gets no empty catalogue", collect("he", packs) == [])

        check("building writes every catalogue that has packs", build(packs) == 0)
        check("English lands where PackService asks for it", (packs / "manifest.json").is_file())
        check("Russian lands one folder down, where PackService asks for it",
              (packs / "ru" / MANIFEST).is_file())
        check("Hebrew gets no file rather than an empty one",
              not (packs / "he" / MANIFEST).exists())
        check("a written catalogue is what the device parses",
              json.loads((packs / MANIFEST).read_text())["packs"][0]["id"] == "geography")

        check("--check passes once everything is written", build(packs, check=True) == 0)
        (packs / "community" / "topic-space-science.json").write_text(
            json.dumps(pack("science", "topic-space-science")))
        check("--check fails the moment a published pack is not listed",
              build(packs, check=True) == 1)
        check("and --check does not fix it behind your back",
              len(json.loads((packs / MANIFEST).read_text())["packs"]) == 2)
        check("building again picks the new pack up", build(packs) == 0
              and len(json.loads((packs / MANIFEST).read_text())["packs"]) == 3)

        # A rebuild with no content change must not rewrite the file: `content.yml` commits
        # whatever changed, and a manifest whose only difference is its timestamp would produce
        # an empty commit on every run and retrigger everything watching main.
        before = (packs / MANIFEST).read_text()
        build(packs)
        check("an unchanged catalogue is not rewritten, so no run commits a new timestamp",
              (packs / MANIFEST).read_text() == before)

        (packs / "he").mkdir()
        (packs / "he" / "geography.json").write_text(
            json.dumps(pack("geography", "geography-ru", "ru")))
        check("a pack whose declared language contradicts its folder is skipped, not miscatalogued",
              collect("he", packs) == [])

        # The bundled twin. A stamped pack whose APK copy still names no version is the same
        # disagreement moved one file along, and the device re-downloads on every refresh.
        assets = Path(tmp) / "assets"
        (assets / "ru").mkdir(parents=True)
        (packs / "ru" / "geography.json").write_text(
            json.dumps(pack("geography", "geography-ru", "ru")))
        (assets / "ru" / "geography.json").write_text(
            json.dumps(pack("geography", "geography-ru", "ru")))
        (packs / "community" / "topic-tides-geography.json").write_text(
            json.dumps(pack("geography", "topic-tides-geography")))
        build(packs, assets=assets)
        check("a stamped pack's bundled twin is stamped with it, and identically",
              (assets / "ru" / "geography.json").read_text()
              == (packs / "ru" / "geography.json").read_text())
        check("a topic pack is not copied into the APK, which is why it was downloadable at all",
              not (assets / "community").exists())
        check("a pack tree that is not the repository's mirrors nowhere by default",
              mirror_of(packs / "ru" / "geography.json", packs) is None)
        check("and the repository's own tree still mirrors where the APK reads",
              mirror_of(PACKS / "ru" / "geography.json") == ASSETS / "ru" / "geography.json")

    print(f"\n{len(failures)} failed" if failures else "\nAll checks passed.")
    return 1 if failures else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true",
                        help="report staleness and change nothing")
    parser.add_argument("--language", action="append", choices=LANGUAGES,
                        help="just this one (repeatable)")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    return build(languages=args.language, check=args.check)


if __name__ == "__main__":
    sys.exit(main())
