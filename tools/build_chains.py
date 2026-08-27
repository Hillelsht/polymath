#!/usr/bin/env python3
"""Publish the daily Chains grids.

Sixteen tiles concealing four groups of four. The grids are built here and published to
`packs/play/chains/YYYY-MM.json` rather than generated on the phone, for one reason: **the whole
appeal of a daily puzzle is that it is the same one everybody else got**, and devices hold
different subsets of the library once the top-up has been running for a while. A device
shuffling locally would be playing a different puzzle to the person it is being compared with.

The rule that decides whether a grid is any good is the overlap rule. A tile belonging to two
groups gives the grid no single solution, and a solver who spots the wrong-but-defensible
grouping is told they are wrong by a puzzle that is itself wrong. It is the defining failure of
this format and it cannot be seen by looking at a grid.

This generator makes that impossible by construction rather than by checking afterwards: a
string is only ever eligible as a tile if it is the answer to exactly one thing in the entire
corpus. Then it checks afterwards as well, because a rule worth relying on is worth proving.

Unlike `generate_facts.py` this needs no network at all — it reads the published library off
disk — so it runs anywhere, including where it was written.
"""

import argparse
import json
import random
import re
import sys
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

LANGUAGES = ["en", "ru", "he"]
DEFAULT_LANGUAGE = "en"


# English resolves to the paths this pipeline has always used, so every published grid and every
# installed app keeps hitting the exact URLs it always has. Any other language sits under its own
# tag — the same convention `PackService` already resolves for the library and the catalogue.
#
# The output stays under `packs/play/` rather than moving to `packs/<tag>/play/`, because
# `fetchGamePack` joins a relative path onto `packs/play/` and a translated grid should not need
# an app release to become reachable. `chains/ru/2026-08.json` is a path it can already fetch.
def library_dir(language=DEFAULT_LANGUAGE):
    return ROOT / "packs" / "library" if language == DEFAULT_LANGUAGE \
        else ROOT / "packs" / language / "library"


def bundled_dir(language=DEFAULT_LANGUAGE):
    base = ROOT / "app" / "src" / "main" / "assets" / "packs"
    return base if language == DEFAULT_LANGUAGE else base / language


def out_dir(language=DEFAULT_LANGUAGE):
    base = ROOT / "packs" / "play" / "chains"
    return base if language == DEFAULT_LANGUAGE else base / language

GROUP_COUNT = 4
GROUP_SIZE = 4

# A tile is a quarter of a phone screen wide. "Constantinople" already needs the small type;
# anything longer stops being readable and starts being a wrapping problem.
MAX_TILE_CHARS = 18

# Below this a group would be drawn from the same handful of answers every time it appeared.
MIN_POOL_PER_TYPE = 12

# When at least this proportion of a type's answers are things the corpus writes facts *about*,
# the ones that are not are treated as miscategorised and dropped. See `written_about_types`.
#
# The measured split is not close: countries 78%, athletes 100%, elements 100% on one side;
# mountain ranges 15%, currencies 2%, chemical symbols 0% on the other. Nothing sits between 32%
# and 61%, so the threshold is placed in open ground rather than fitted to the data.
TRUSTED_TYPE_FRACTION = 0.5

NOT_A_PACK = {"channels.json", "durations.json", "manifest.json", "index.json"}

# A fact whose subject is this far below the corpus's own median is not a hard question, it is a
# question about something the world has not written about. "What is the chemical symbol for
# unquadoctium?" scores 8 where tantalum scores 146 — unquadoctium is a hypothetical element that
# has never been made, and its symbol is a naming convention rather than a fact.
#
# 15 is where the cliff is, not a preference: it removes 34 facts, 33 of them these placeholder
# elements. At 18 the count triples and starts taking real sculptors and real countries with it.
#
# Facts with no stated importance are hand-authored — the Mona Lisa, Guernica — and are exempt.
# They are the best content in the corpus and a naive floor would delete all of it.
MIN_SUBJECT_IMPORTANCE = 15

# What a group of four is called once it is revealed. An answerType with no entry here falls
# back to its own name, which reads acceptably ("composer") but not well.
# What a group of four is called once it is revealed, in every language the app ships.
#
# The `answerType` key stays English in every language — that is the invariant in
# `docs/invariants.md`, and it is what lets one table serve all three: the type is the key that
# groups distractors, and translating it would split each type per language and starve the quiz.
# Only the *label* is translated, because the label is the only part a player reads.
#
# A type with no entry for the language being built is **dropped**, not fallen back on. The
# fallback capitalises the type's own English name, which reads acceptably in an English grid and
# reads as a bug in a Russian one — a group revealed as "Composer" in the middle of a Russian
# puzzle is worse than that group not appearing.
LABELS = {
    "capital": {"en": "Capital cities", "ru": "Столицы", "he": "ערי בירה"},
    "currency": {"en": "Currencies", "ru": "Валюты", "he": "מטבעות"},
    "continent": {"en": "Continents", "ru": "Континенты", "he": "יבשות"},
    "body of water": {"en": "Rivers empty into these", "ru": "Куда впадают реки",
                      "he": "לשם נשפכים נהרות"},
    "mountain range": {"en": "Mountain ranges", "ru": "Горные хребты", "he": "רכסי הרים"},
    "chemical symbol": {"en": "Chemical symbols", "ru": "Химические символы",
                        "he": "סמלים כימיים"},
    "astronomical body": {"en": "Orbited by something", "ru": "Вокруг них обращаются",
                          "he": "משהו מקיף אותם"},
    "discoverer": {"en": "Discovered something", "ru": "Первооткрыватели", "he": "מגלים"},
    "namesake": {"en": "Things are named after these", "ru": "В их честь названо",
                 "he": "על שמם נקראים דברים"},
    "painter": {"en": "Painters", "ru": "Художники", "he": "ציירים"},
    "author": {"en": "Authors", "ru": "Писатели", "he": "סופרים"},
    "sculptor": {"en": "Sculptors", "ru": "Скульпторы", "he": "פסלים"},
    "composer": {"en": "Wrote the music", "ru": "Написали музыку", "he": "כתבו את המוזיקה"},
    "war": {"en": "Wars", "ru": "Войны", "he": "מלחמות"},
    "director": {"en": "Directed films", "ru": "Режиссёры", "he": "ביימו סרטים"},
    "country": {"en": "Countries", "ru": "Страны", "he": "מדינות"},
    "sport": {"en": "Sports", "ru": "Виды спорта", "he": "ענפי ספורט"},
    "genus": {"en": "Genera", "ru": "Роды", "he": "סוגים ביולוגיים"},
    "birthplace": {"en": "Birthplaces", "ru": "Места рождения", "he": "מקומות לידה"},
    "writing system": {"en": "Writing systems", "ru": "Системы письма", "he": "שיטות כתב"},
    # These four were falling through to the fallback, which capitalises the type's own name and
    # leaves it singular. A group revealed as "Historical-figure" — hyphen and all — reads as a
    # database field rather than as an answer, and the reveal is the moment the grid is judged.
    "historical-figure": {"en": "Historical figures", "ru": "Исторические личности",
                          "he": "דמויות היסטוריות"},
    "athlete": {"en": "Athletes", "ru": "Спортсмены", "he": "ספורטאים"},
    "musician": {"en": "Musicians", "ru": "Музыканты", "he": "מוזיקאים"},
    "element": {"en": "Elements", "ru": "Химические элементы", "he": "יסודות כימיים"},
    # The civics templates, minus one. "head of state" is deliberately absent: a grid is
    # published up to four months ahead and then never rewritten, so a group of four names under
    # "Heads of state" stops being true the first time one of them leaves office — and the
    # player, not the table, is the one told they are wrong. The other three answer with
    # institutions, forms and languages, which outlast a publishing window.
    "legislature": {"en": "Legislatures", "ru": "Парламенты", "he": "בתי מחוקקים"},
    "form of government": {"en": "Forms of government", "ru": "Формы правления",
                           "he": "צורות ממשל"},
    "official language": {"en": "Official languages", "ru": "Официальные языки",
                          "he": "שפות רשמיות"},
}


def load_facts(language=DEFAULT_LANGUAGE):
    """Every fact on disk for one language: its published library plus what the app bundles.

    A language reads only its own corpus. Mixing them would put a Russian tile in an English grid,
    and the answers are the tiles here — this is the one game in the app whose *content* is the
    text on screen rather than a picture or a number.
    """
    facts = []
    for directory in (library_dir(language), bundled_dir(language)):
        if not directory.exists():
            continue
        for path in sorted(directory.glob("*.json")):
            if path.name in NOT_A_PACK:
                continue
            try:
                body = json.loads(path.read_text())
            except Exception:
                continue
            for fact in body.get("facts", []):
                if not (fact.get("answer") and fact.get("answerType")):
                    continue
                if 0 < float(fact.get("importance") or 0) < MIN_SUBJECT_IMPORTANCE:
                    continue
                facts.append(fact)
    return facts


# Punctuation a real answer can contain. Everything else — digits, brackets, slashes, the
# parenthetical disambiguations Wikidata labels carry — reads as noise on a tile and rarely groups
# cleanly. The apostrophes are listed in all three shapes a corpus actually produces: ASCII, the
# typographic one Wikipedia prefers, and the Hebrew geresh.
TILE_PUNCTUATION = " .'-\u2019\u05f3"


def readable_tile(answer):
    """Whether a string is the kind of thing that can sit on a tile.

    This used to be `[A-Za-z][A-Za-z .'À-ɏ-]*`, which is a Latin-alphabet test wearing a
    character-class costume: the range stops at U+024F, so **every Cyrillic and every Hebrew
    answer failed it**. Nothing said so. The Russian build would simply have found no eligible
    answers, reported zero usable types, published nothing, and exited 0 — a daily that does not
    exist in two of the three languages the app ships, with no error anywhere to explain it.

    `str.isalpha()` is Unicode-aware and says the same thing about a Latin letter that the old
    range did, so English grids are unchanged: a digit is not alphabetic, so "Q123" and "1984"
    are still refused, and a leading letter is still required.
    """
    if len(answer) < 2 or not answer[0].isalpha():
        return False
    return all(c.isalpha() or c in TILE_PUNCTUATION for c in answer)


def eligible_answers(facts):
    """Answers that can safely be tiles, grouped by their answerType.

    The filter that matters: an answer used by more than one answerType is thrown away
    entirely. That is what makes a tile-in-two-groups grid unbuildable rather than merely
    unlikely — Paris is a capital, but if it is also somebody's birthplace in this corpus then
    it cannot appear at all.
    """
    types_by_answer = defaultdict(set)
    for fact in facts:
        answer = fact["answer"].strip()
        if answer:
            types_by_answer[answer].add(fact["answerType"])

    # How well known the *tile* is — which is a different question from how well known the fact
    # it came from is, and getting them confused ruins the reveal order.
    #
    # Two metrics were tried and both rank the wrong thing. The fact's own difficulty is derived
    # from the sitelinks of its *subject*, so the composer of a famous film scores as easy while
    # his name, the thing actually on the tile, is not something most people could place. Raw
    # frequency in the corpus is worse in its own way: a mountain range is the answer for every
    # mountain in it, so obscure ranges outrank Gold and Iron.
    #
    # `wikiTitle` is the subject's own article title, so a fact about Poland carries the fame of
    # Poland. Answers that appear as somebody's subject somewhere in the corpus therefore have a
    # real score of their own, and answers that never do are treated as obscure — which is very
    # nearly what being absent from 4,000 facts means.
    UNKNOWN_FAME = 4.0
    fame_of = {}
    for fact in facts:
        title = (fact.get("wikiTitle") or "").strip()
        if not title:
            continue
        seen = float(fact.get("difficulty", 2))
        fame_of[title] = min(fame_of.get(title, seen), seen)

    # Some answers are never anybody's subject — no fact in the corpus is *about* basketball,
    # only about people who play it — so they all land on the same obscure score and a group of
    # household-name sports sorts below a group of Alpine sub-ranges. How often an answer is
    # used breaks that tie in the right direction without ever letting an unknown outrank a
    # genuinely famous subject.
    frequency_of = defaultdict(int)
    for fact in facts:
        frequency_of[fact["answer"].strip()] += 1

    def fame(answer):
        known = fame_of.get(answer)
        if known is not None:
            return known
        return UNKNOWN_FAME - min(0.9, frequency_of[answer] / 20.0)

    pools = defaultdict(list)
    for answer, types in types_by_answer.items():
        if len(types) != 1:
            continue
        if len(answer) > MAX_TILE_CHARS or len(answer) < 2:
            continue
        if not readable_tile(answer):
            continue
        pools[next(iter(types))].append((answer, fame(answer)))

    # Drop the answers that were filed under a type they do not belong to. Measured on the whole
    # pool, before the size cut below, so a type is judged on everything it has.
    trusted, subjects = written_about_types(facts, pools)
    pools = {
        answer_type: [e for e in entries if answer_type not in trusted or e[0] in subjects]
        for answer_type, entries in pools.items()
    }

    return {
        answer_type: sorted(entries)
        for answer_type, entries in pools.items()
        if len(entries) >= MIN_POOL_PER_TYPE
    }


def written_about_types(facts, pools):
    """The answer types whose members are the kind of thing this corpus has facts *about*.

    This exists to stop a group's label lying about its members. "Countries" shipped containing
    Xinjiang and the Maghreb; "Authors" shipped containing Moses. Every one of those grids passed
    every check there was, because the overlap rule only asks whether a tile fits *two* groups —
    it has nothing to say about a tile that fits its own group badly. A solver who knows the
    Maghreb is not a country is being told they are wrong by a puzzle that is itself wrong, which
    is the same failure the overlap rule exists to prevent, arriving by a different door.

    The signal is that a real country is something the corpus writes about, and a region that was
    mislabelled as one is not. That only holds where the corpus writes about that kind of thing
    at all: it has facts about countries and athletes, and none about currencies or chemical
    symbols, where being absent means nothing. So the test is applied per type and only where the
    type as a whole clears [TRUSTED_TYPE_FRACTION] — which is what stops it deleting every
    currency in the corpus.

    It is deliberately biased. Tolkien and Lewis Carroll are dropped from the authors along with
    Moses, because this corpus happens to hold no fact about either man. Losing a good tile costs
    a puzzle nothing anyone can see; shipping "Countries: Maghreb" is the kind of mistake a player
    only needs to meet once.
    """
    subjects = {(fact.get("wikiTitle") or "").strip() for fact in facts}
    subjects.discard("")
    trusted = set()
    for answer_type, entries in pools.items():
        written = sum(1 for answer, _ in entries if answer in subjects)
        if written >= TRUSTED_TYPE_FRACTION * len(entries):
            trusted.add(answer_type)
    return trusted, subjects


def label_for(answer_type, language=DEFAULT_LANGUAGE):
    """What the group is called, or None if this language has no name for it.

    None is a real answer and the caller drops the type. English keeps the old fallback, because
    an English label derived from an English type name is a slightly clumsy label rather than a
    wrong one — and because dropping types in English would change grids already published.
    """
    entry = LABELS.get(answer_type)
    if entry and entry.get(language):
        return entry[language]
    if language == DEFAULT_LANGUAGE:
        return answer_type[:1].upper() + answer_type[1:]
    return None


def nameable(pools, language=DEFAULT_LANGUAGE):
    """The pools this language can actually put a name to, and a note about what it dropped."""
    keep = {t: entries for t, entries in pools.items() if label_for(t, language) is not None}
    dropped = sorted(set(pools) - set(keep))
    return keep, dropped


def published_days(path):
    """The grids already published for a month, keyed by date. Empty if there is no file yet.

    This is what stops a rebuild rewriting a day somebody has already played, and it is not a
    nicety. `build_grid` is deterministic in the *date*, which sounds like enough and is not: it
    samples from pools, so **one new eligible answer anywhere in the corpus reshuffles every grid
    in every month**. Adding Unicode support to the tile filter changed all 122 published English
    days at once, which is how this was noticed — and the library regenerates monthly, so the same
    thing had been quietly happening on its own schedule. Somebody who shared a result on the 1st
    would find the puzzle it described no longer existed.

    `PublishRooms.kt` has followed this rule for the daily rooms since they existed, for the same
    reason: a shared result names a date, so the date has to keep meaning what it meant.
    """
    if not Path(path).is_file():
        return {}
    try:
        pack = json.loads(Path(path).read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return {}
    return {grid["date"]: grid for grid in pack.get("puzzles", []) if grid.get("date")}


def build_grid(pools, day, language=DEFAULT_LANGUAGE):
    """One grid, deterministic in the date so a re-run republishes the same puzzle.

    Each language builds its own grid from its own corpus, seeded by the same date. They are
    therefore *different puzzles* on the same day, not one puzzle translated — and that is a
    limit worth stating rather than discovering. Building one shared grid would mean intersecting
    three corpora that do not hold the same facts, and every language would then be reduced to
    what the thinnest of them can support. A Russian player comparing a share grid with another
    Russian player is comparing the same puzzle, which is what sharing is actually for.
    """
    rng = random.Random(f"chains-{day.isoformat()}")
    types = sorted(pools)
    if len(types) < GROUP_COUNT:
        return None

    chosen_types = rng.sample(types, GROUP_COUNT)
    groups = []
    used = set()
    for answer_type in chosen_types:
        candidates = [a for a, _ in pools[answer_type] if a not in used]
        if len(candidates) < GROUP_SIZE:
            return None
        members = rng.sample(candidates, GROUP_SIZE)
        used.update(members)
        obscurity = sum(f for a, f in pools[answer_type] if a in members) / GROUP_SIZE
        groups.append({
            "id": answer_type.replace(" ", "-"),
            "label": label_for(answer_type, language),
            "members": members,
            "obscurity": obscurity,
        })

    # 1 is the best-known, 4 the most obscure. Difficulty 1 is revealed first and is the
    # solver's foothold, so it has to be the group they can actually see.
    groups.sort(key=lambda g: g["obscurity"])
    for index, group in enumerate(groups):
        group["difficulty"] = index + 1
        del group["obscurity"]

    tiles = [m for g in groups for m in g["members"]]
    rng.shuffle(tiles)
    return {"date": day.isoformat(), "tiles": tiles, "groups": groups}


def problems(grid):
    """The same checks ChainsPuzzle.problems() runs on the device, run before publishing."""
    found = []
    groups = grid["groups"]
    if len(groups) != GROUP_COUNT:
        found.append(f"{grid['date']}: {len(groups)} groups")
    for group in groups:
        if len(group["members"]) != GROUP_SIZE:
            found.append(f"{grid['date']}: group '{group['label']}' has {len(group['members'])}")
    tiles = grid["tiles"]
    if len(tiles) != GROUP_COUNT * GROUP_SIZE:
        found.append(f"{grid['date']}: {len(tiles)} tiles")
    if len(set(tiles)) != len(tiles):
        found.append(f"{grid['date']}: repeats a tile")

    members = [m for g in groups for m in g["members"]]
    if set(members) != set(tiles):
        found.append(f"{grid['date']}: tiles and groups describe different things")
    for member, count in {m: members.count(m) for m in members}.items():
        if count > 1:
            found.append(f"{grid['date']}: '{member}' is in two groups — no single solution")
    if len({g["id"] for g in groups}) != len(groups):
        found.append(f"{grid['date']}: two groups share an id")
    if len({g["difficulty"] for g in groups}) != len(groups):
        found.append(f"{grid['date']}: two groups share a difficulty")
    return found


def months_from(start, count):
    """The first day of `count` consecutive months, starting with the one `start` is in."""
    cursor = start.replace(day=1)
    for _ in range(count):
        yield cursor
        cursor = (cursor.replace(day=28) + timedelta(days=7)).replace(day=1)


def days_in(month_start):
    day = month_start
    while day.month == month_start.month:
        yield day
        day += timedelta(days=1)


def self_test():
    """Everything checkable without the corpus, so a logic bug fails in a second."""
    failures = []

    def check(name, condition):
        print(f"  {'ok  ' if condition else 'FAIL'} {name}")
        if not condition:
            failures.append(name)

    # --- facts about nothing ----------------------------------------------------------------
    # load_facts reads from disk, so this exercises the rule directly rather than through it.
    keep = {"answer": "Ta", "answerType": "chemical symbol", "importance": 146}
    drop = {"answer": "Uqo", "answerType": "chemical symbol", "importance": 8}
    hand = {"answer": "Mona Lisa", "answerType": "painting"}
    usable = lambda f: not (0 < float(f.get("importance") or 0) < MIN_SUBJECT_IMPORTANCE)
    check("a fact about a well-known subject is kept", usable(keep))
    check("a fact about a subject nobody has written about is dropped", not usable(drop))
    check("a hand-authored fact, which states no importance, is exempt", usable(hand))

    facts = [
        {"answer": "Paris", "answerType": "capital", "difficulty": 1},
        {"answer": "Lisbon", "answerType": "capital", "difficulty": 1},
        {"answer": "Oslo", "answerType": "capital", "difficulty": 2},
        {"answer": "Sofia", "answerType": "capital", "difficulty": 2},
        {"answer": "Sofia", "answerType": "birthplace", "difficulty": 3},
    ]
    pools = eligible_answers(facts)
    check(
        "an answer used by two types is dropped entirely",
        all("Sofia" not in [a for a, _ in entries] for entries in pools.values()),
    )

    check(
        "a type with too few answers to vary is dropped",
        eligible_answers([{"answer": f"A{i}", "answerType": "thin", "difficulty": 1}
                          for i in range(MIN_POOL_PER_TYPE - 1)]) == {},
    )

    # --- the label has to tell the truth about the members ------------------------------------
    # A type the corpus writes about, with one answer filed under it that it does not write about.
    # That answer is the Maghreb filed under "country", and it is what this rule exists to remove.
    countries = ["Chad", "Peru", "Fiji", "Oman", "Malta", "Nepal", "Togo", "Cuba",
                 "Kenya", "Ghana", "Laos", "Iran"]
    corpus = (
        [{"answer": c, "answerType": "country", "difficulty": 1} for c in countries + ["Maghreb"]]
        # Each country is also written about, which is what makes the type trustworthy. The
        # Maghreb is not, which is what singles it out.
        + [{"answer": "x", "answerType": "other", "wikiTitle": c, "difficulty": 1}
           for c in countries]
    )
    pool = [a for a, _ in eligible_answers(corpus).get("country", [])]
    check("a type the corpus writes about keeps the answers it writes about", set(pool) == set(countries))
    check("and drops the one it does not, which was filed under the wrong type", "Maghreb" not in pool)

    # The same shape, with nothing written about at all: currencies and chemical symbols are
    # never anybody's subject, so absence carries no information and must not delete the type.
    untouched = [{"answer": f"currency{chr(97 + i)}", "answerType": "currency", "difficulty": 1}
                 for i in range(MIN_POOL_PER_TYPE + 1)]
    check(
        "a type the corpus never writes about is left alone",
        len(eligible_answers(untouched).get("currency", [])) == MIN_POOL_PER_TYPE + 1,
    )

    long_name = "A" * (MAX_TILE_CHARS + 1)
    check(
        "an answer too long for a tile is dropped",
        long_name not in [
            a for entries in eligible_answers(
                [{"answer": long_name, "answerType": "t", "difficulty": 1}]
            ).values() for a, _ in entries
        ],
    )

    # Letters only: the tile filter rejects digits, so a fixture built from "Answer0" would
    # test nothing but the filter.
    def synthetic(type_count=6, per_type=20):
        return [
            {"answer": f"{chr(65 + t)}nswer{chr(97 + i)}", "answerType": f"type{t}",
             "difficulty": 1 + i % 3}
            for t in range(type_count) for i in range(per_type)
        ]

    pools = eligible_answers(synthetic())
    grid = build_grid(pools, date(2026, 8, 11))
    check("a grid is built", grid is not None)

    if grid is not None:
        check("a built grid has nothing wrong with it", problems(grid) == [])
        check("a grid is sixteen tiles", len(grid["tiles"]) == 16)
        check(
            "difficulties run 1 to 4",
            sorted(g["difficulty"] for g in grid["groups"]) == [1, 2, 3, 4],
        )
        check("the same day always builds the same grid", build_grid(pools, date(2026, 8, 11)) == grid)
        check(
            "a different day builds a different grid",
            build_grid(pools, date(2026, 8, 12)) != grid,
        )

        broken = json.loads(json.dumps(grid))
        broken["groups"][1]["members"][0] = broken["groups"][0]["members"][0]
        broken["tiles"] = [m for g in broken["groups"] for m in g["members"]]
        check(
            "a tile in two groups is caught",
            any("no single solution" in p for p in problems(broken)),
        )

    check(
        "a corpus with too few usable types builds nothing rather than half a grid",
        build_grid(eligible_answers(synthetic(type_count=2)), date(2026, 8, 11)) is None,
    )

    # --- three languages -----------------------------------------------------------------
    check("every group label is written in every language the app ships",
          all(set(entry) == set(LANGUAGES) and all(entry.values())
              for entry in LABELS.values()))
    absent = [t for t, e in LABELS.items() if set(e) != set(LANGUAGES)]
    check(f"...and none is missing one{'' if not absent else f': {absent}'}", not absent)

    check("English still falls back to the type's own name, so published grids do not change",
          label_for("brand-new-type") == "Brand-new-type")
    check("a language with no name for a type says so rather than printing English",
          label_for("brand-new-type", "ru") is None)
    check("a type it can name comes back translated", label_for("capital", "ru") == "Столицы")
    check("and in Hebrew too", label_for("capital", "he") == "ערי בירה")

    named = {"capital": [("Paris", 1.0)], "brand-new-type": [("Thing", 1.0)]}
    kept, dropped = nameable(named, "ru")
    check("an unnameable type is dropped from a translated build", set(kept) == {"capital"})
    check("and named in the report, because the fix is one row in LABELS",
          dropped == ["brand-new-type"])
    check("English drops nothing, because it can name everything", nameable(named)[1] == [])

    check("English reads and writes exactly where it always has",
          library_dir().name == "library" and out_dir().parts[-2:] == ("play", "chains"))
    check("a translated library sits under its own tag",
          library_dir("ru").parts[-2:] == ("ru", "library"))
    check("and its grids sit one level below English, not beside it",
          out_dir("ru").parent == out_dir() and out_dir("ru").name == "ru")
    check("a bundled translated pack is looked for under its tag too",
          bundled_dir("he").name == "he" and bundled_dir().name == "packs")

    # A grid built in Russian must carry Russian labels — the check that would have caught a
    # translated build quietly emitting the English table.
    check("a Latin answer is a readable tile", readable_tile("Buenos Aires"))
    check("so is a Cyrillic one, which the old Latin-only filter refused outright",
          readable_tile("Буэнос-Айрес"))
    check("and a Hebrew one", readable_tile("בואנוס איירס"))
    check("an accented Latin answer still passes, as it always did", readable_tile("Zürich"))
    check("a Q-number is not a tile", not readable_tile("Q1234"))
    check("nor is a year", not readable_tile("1984"))
    check("nor is a parenthetical disambiguation", not readable_tile("Pietà (Michelangelo)"))
    check("nor is a single letter", not readable_tile("H"))

    types = ("capital", "currency", "continent", "painter")
    # Cyrillic throughout and no digits, because a fixture that could pass the old Latin-only
    # filter would not be testing anything this change is about.
    ru_facts = [{"answer": f"{chr(0x410 + t)}твет{chr(0x430 + i)}", "answerType": kind,
                 "difficulty": 1 + i % 3}
                for t, kind in enumerate(types) for i in range(20)]
    ru_pools, _ = nameable(eligible_answers(ru_facts), "ru")
    ru_grid = build_grid(ru_pools, date(2026, 9, 1), "ru")
    check("a Russian grid is buildable from a Russian corpus", ru_grid is not None)
    check("and every label on it is Russian",
          ru_grid is not None and all(
              g["label"] in {v["ru"] for v in LABELS.values()} for g in ru_grid["groups"]))
    check("and it still passes the rules the device applies",
          ru_grid is not None and problems(ru_grid) == [])
    en_grid = build_grid(nameable(eligible_answers(ru_facts))[0], date(2026, 9, 1))
    check("the same corpus in English produces English labels, from one shared table",
          en_grid is not None and all(
              g["label"] in {v["en"] for v in LABELS.values()} for g in en_grid["groups"]))

    # --- a published day is never rewritten ------------------------------------------------
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        month = Path(tmp) / "2026-09.json"
        check("no file yet means nothing is published", published_days(month) == {})
        first = build_grid(ru_pools, date(2026, 9, 1), "ru")
        month.write_text(json.dumps({"month": "2026-09", "puzzles": [first]}), encoding="utf-8")
        check("a written month reads back keyed by date",
              list(published_days(month)) == ["2026-09-01"])
        check("and reads back the very same grid", published_days(month)["2026-09-01"] == first)

        # The failure this exists for, reproduced: one extra answer in one pool, and dates that
        # used to build one grid build another. Checked across a fortnight rather than on a single
        # day, because any one date may happen to resample the same four answers — which is
        # precisely why "it looked fine when I spot-checked it" is not evidence here. On the real
        # corpus this change moved all 122 published days.
        wider = {t: list(v) for t, v in ru_pools.items()}
        wider[sorted(wider)[0]].append(("Новыйответ", 1.0))
        moved = sum(1 for d in range(1, 15)
                    if build_grid(wider, date(2026, 9, d), "ru")
                    != build_grid(ru_pools, date(2026, 9, d), "ru"))
        check(f"one new eligible answer changes what dates build ({moved}/14 days)", moved > 0)
        check("...which is exactly why the published one is kept instead",
              published_days(month)["2026-09-01"] == first)

        month.write_text("{not json", encoding="utf-8")
        check("a corrupt month is treated as unpublished rather than crashing the run",
              published_days(month) == {})

    starts = list(months_from(date(2026, 11, 3), 3))
    check(
        "months roll over the year",
        starts == [date(2026, 11, 1), date(2026, 12, 1), date(2027, 1, 1)],
    )
    check("a 31-day month yields 31 days", len(list(days_in(date(2026, 8, 1)))) == 31)
    check("February is handled", len(list(days_in(date(2027, 2, 1)))) == 28)

    print(f"\n{len(failures)} failed" if failures else "\nAll checks passed.")
    return 1 if failures else 0


def publish(language, start, months):
    """One language's grids. Returns (published, skipped, problems) — problems fail the whole run."""
    facts = load_facts(language)
    print(f"\n[{language}] {len(facts)} facts on disk.")
    if not facts:
        print(f"  no library published for {language} yet — nothing to build from")
        return 0, 0, []

    pools = eligible_answers(facts)
    pools, unnameable = nameable(pools, language)
    if unnameable:
        # Said out loud rather than silently skipped: a type the corpus can fill and this table
        # cannot name is a group the language is missing, and the fix is one row in LABELS.
        print(f"  no {language} label for: {', '.join(unnameable)} — those groups cannot appear")
    print(f"  {len(pools)} answer types usable as groups:")
    for answer_type, entries in sorted(pools.items(), key=lambda kv: -len(kv[1])):
        print(f"    {answer_type:<20} {len(entries):>4} answers")

    if len(pools) < GROUP_COUNT:
        print(f"  only {len(pools)} usable types, need {GROUP_COUNT} — publishing nothing for "
              f"{language}")
        return 0, 0, []

    published, kept, skipped, all_problems = 0, 0, 0, []
    directory = out_dir(language)
    directory.mkdir(parents=True, exist_ok=True)

    for month_start in months_from(start, months):
        name = f"{month_start.strftime('%Y-%m')}.json"
        already = published_days(directory / name)
        grids = []
        for day in days_in(month_start):
            iso = day.isoformat()
            if iso in already:
                grids.append(already[iso])
                kept += 1
                continue
            grid = build_grid(pools, day, language)
            if grid is None:
                skipped += 1
                continue
            found = problems(grid)
            if found:
                all_problems += [f"{language} {p}" for p in found]
                skipped += 1
                continue
            grids.append(grid)
            published += 1

        if not grids:
            continue
        (directory / name).write_text(
            json.dumps({"month": month_start.strftime("%Y-%m"), "language": language,
                        "puzzles": sorted(grids, key=lambda g: g["date"])},
                       ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        print(f"  {name}  {len(grids)} grids ({kept} already published)"
              if kept else f"  {name}  {len(grids)} grids")

    return published, skipped, all_problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--months", type=int, default=3, help="how many months to publish")
    parser.add_argument("--start", default=None, help="YYYY-MM-DD, defaults to today")
    parser.add_argument("--language", action="append", choices=LANGUAGES,
                        help="just this one (repeatable); defaults to every language")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    start = date.fromisoformat(args.start) if args.start else date.today()
    languages = args.language or LANGUAGES

    total, skipped, all_problems, built = 0, 0, [], []
    for language in languages:
        published, missed, found = publish(language, start, args.months)
        total += published
        skipped += missed
        all_problems += found
        if published or out_dir(language).exists():
            built.append(language)

    if all_problems:
        # Never publish a grid that would tell a player they are wrong when they are right.
        print("\nFAIL — grids that would have been broken:")
        for problem in all_problems[:20]:
            print(f"  - {problem}")
        return 1

    if total == 0 and not any(out_dir(l).exists() for l in languages):
        print("\nFAIL: nothing publishable in any language. Leaving what is there alone.")
        return 1

    # A language asked for and not built is not a failure — Russian and Hebrew had no library at
    # all until recently, and a run that refused to publish English over it would be refusing the
    # work that succeeded. It is reported, because silence here reads as success.
    missing = [l for l in languages if l not in built]
    if missing:
        print(f"\nNothing published for: {', '.join(missing)}")

    size = sum(f.stat().st_size for language in built for f in out_dir(language).glob("*.json"))
    print(f"\n{total} grids published across {', '.join(built)}, {size // 1024} KB. "
          f"{skipped} days skipped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
