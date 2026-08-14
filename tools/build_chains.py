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
LIBRARY = ROOT / "packs" / "library"
BUNDLED = ROOT / "app" / "src" / "main" / "assets" / "packs"
OUT = ROOT / "packs" / "play" / "chains"

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
LABELS = {
    "capital": "Capital cities",
    "currency": "Currencies",
    "continent": "Continents",
    "body of water": "Rivers empty into these",
    "mountain range": "Mountain ranges",
    "chemical symbol": "Chemical symbols",
    "astronomical body": "Orbited by something",
    "discoverer": "Discovered something",
    "namesake": "Things are named after these",
    "painter": "Painters",
    "author": "Authors",
    "sculptor": "Sculptors",
    "composer": "Wrote the music",
    "war": "Wars",
    "director": "Directed films",
    "country": "Countries",
    "sport": "Sports",
    "genus": "Genera",
    "birthplace": "Birthplaces",
    "writing system": "Writing systems",
    # These four were falling through to the fallback, which capitalises the type's own name and
    # leaves it singular. A group revealed as "Historical-figure" — hyphen and all — reads as a
    # database field rather than as an answer, and the reveal is the moment the grid is judged.
    "historical-figure": "Historical figures",
    "athlete": "Athletes",
    "musician": "Musicians",
    "element": "Elements",
}


def load_facts():
    """Every fact on disk: the published library plus what the app bundles."""
    facts = []
    for directory in (LIBRARY, BUNDLED):
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
        # Digits and punctuation read as noise on a tile and rarely group cleanly.
        if not re.fullmatch(r"[A-Za-z][A-Za-z .'À-ɏ-]*", answer):
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


def label_for(answer_type):
    return LABELS.get(answer_type, answer_type[:1].upper() + answer_type[1:])


def build_grid(pools, day):
    """One grid, deterministic in the date so a re-run republishes the same puzzle."""
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
            "label": label_for(answer_type),
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

    starts = list(months_from(date(2026, 11, 3), 3))
    check(
        "months roll over the year",
        starts == [date(2026, 11, 1), date(2026, 12, 1), date(2027, 1, 1)],
    )
    check("a 31-day month yields 31 days", len(list(days_in(date(2026, 8, 1)))) == 31)
    check("February is handled", len(list(days_in(date(2027, 2, 1)))) == 28)

    print(f"\n{len(failures)} failed" if failures else "\nAll checks passed.")
    return 1 if failures else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--months", type=int, default=3, help="how many months to publish")
    parser.add_argument("--start", default=None, help="YYYY-MM-DD, defaults to today")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    facts = load_facts()
    print(f"{len(facts)} facts on disk.")
    pools = eligible_answers(facts)
    print(f"{len(pools)} answer types are usable as groups:")
    for answer_type, entries in sorted(pools.items(), key=lambda kv: -len(kv[1])):
        print(f"  {answer_type:<20} {len(entries):>4} answers")

    if len(pools) < GROUP_COUNT:
        print(f"\nFAIL: {len(pools)} usable types, need {GROUP_COUNT}. Publishing nothing.")
        return 1

    start = date.fromisoformat(args.start) if args.start else date.today()
    published, skipped, all_problems = 0, 0, []
    OUT.mkdir(parents=True, exist_ok=True)

    for month_start in months_from(start, args.months):
        grids = []
        for day in days_in(month_start):
            grid = build_grid(pools, day)
            if grid is None:
                skipped += 1
                continue
            found = problems(grid)
            if found:
                all_problems += found
                skipped += 1
                continue
            grids.append(grid)

        if not grids:
            continue
        name = f"{month_start.strftime('%Y-%m')}.json"
        (OUT / name).write_text(
            json.dumps({"month": month_start.strftime("%Y-%m"), "puzzles": grids},
                       ensure_ascii=False, separators=(",", ":")),
        )
        published += len(grids)
        print(f"  {name}  {len(grids)} grids")

    if all_problems:
        # Never publish a grid that would tell a player they are wrong when they are right.
        print("\nFAIL — grids that would have been broken:")
        for problem in all_problems[:20]:
            print(f"  - {problem}")
        return 1

    if published == 0:
        print("\nFAIL: nothing publishable. Leaving what is there alone.")
        return 1

    size = sum(f.stat().st_size for f in OUT.glob("*.json"))
    print(f"\n{published} grids published, {size // 1024} KB. {skipped} days skipped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
