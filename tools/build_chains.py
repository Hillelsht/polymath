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

NOT_A_PACK = {"channels.json", "durations.json", "manifest.json", "index.json"}

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
                if fact.get("answer") and fact.get("answerType"):
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

    return {
        answer_type: sorted(entries)
        for answer_type, entries in pools.items()
        if len(entries) >= MIN_POOL_PER_TYPE
    }


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
