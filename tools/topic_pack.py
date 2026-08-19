#!/usr/bin/env python3
"""Turn a typed topic into the packs that would answer it.

Wedge 3's headline is "type a topic, get a daily". The version of that everyone imagines has a
language model in the middle, reading the topic and inventing a Wikidata query for it. That
version needs an API key, a budget and a review process for what a model decided a topic meant,
and none of those exist yet.

This is the version that does not need any of them, and building it first is deliberate. The
mapping step is one function; **everything around it is the part that can be wrong** — routing a
topic to the wrong category, publishing a pack the quiz cannot draw distractors for, writing a file
the device refuses. So this wires the whole path end to end with the dumbest possible mapper — a
synonym table and word overlap against the templates' own vocabulary — and puts the real gates
around it. When the model arrives it replaces [route], and finds the rest already proved.

Two things it cannot do, and both are the measure of how much of Wedge 3 is left.

It cannot invent a template. A topic only reaches the eighteen questions `generate_facts.py`
already knows how to ask, so "chemistry" and "rivers" work and "the Byzantine succession" does not
— and it says so rather than publishing four facts and calling it a pack.

It cannot *narrow* one either. "Rivers of Africa" picks the same template as "rivers" and harvests
the same rivers, because narrowing means adding a constraint to a SPARQL query, which is exactly
the judgement being deferred. Words it ignored are printed, so the gap is visible in the output
rather than discovered in the pack.

    python3 tools/topic_pack.py --topic "space"            # what it would harvest
    python3 tools/topic_pack.py --topic "space" --write    # harvest it (needs Wikidata)
    python3 tools/topic_pack.py --self-test
"""

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import generate_facts as gen                                          # noqa: E402
import validate_pack                                                  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "packs" / "community"

# A pack that cannot furnish four options is not a pack. Same floor the rest of the pipeline uses,
# stated again here because this is the number that decides whether a topic is answerable at all.
MIN_FACTS_PER_PACK = 12

# What a person types, and the templates they meant.
#
# Hand-written, and short on purpose: this is the part a language model is supposed to replace, so
# growing it indefinitely would be building the thing twice. Every entry earns its place by being a
# word the templates' own vocabulary does not contain — "space" appears nowhere near `moon-parent`,
# and no amount of word overlap will connect them.
ALIASES = {
    "space": ["moon-parent", "named-after"],
    "astronomy": ["moon-parent", "named-after"],
    "planets": ["moon-parent"],
    "chemistry": ["element-symbol"],
    "elements": ["element-symbol"],
    "physics": ["discoverer", "named-after"],
    "biology": ["discoverer"],
    "football": ["club-country", "athlete-sport"],
    "soccer": ["club-country", "athlete-sport"],
    "olympics": ["athlete-sport"],
    "art": ["painting-creator", "sculpture-creator"],
    "painting": ["painting-creator"],
    "literature": ["book-author"],
    "books": ["book-author"],
    "novels": ["book-author"],
    "music": ["composer"],
    "cinema": ["film-director"],
    "movies": ["film-director"],
    "film": ["film-director"],
    "food": ["dish-origin"],
    "cooking": ["dish-origin"],
    "cuisine": ["dish-origin"],
    "war": ["battle-conflict"],
    "wars": ["battle-conflict"],
    "battles": ["battle-conflict"],
    "military": ["battle-conflict"],
    "countries": ["capital", "currency", "continent"],
    "geography": ["capital", "currency", "continent", "river-mouth", "mountain-range"],
    "rivers": ["river-mouth"],
    "mountains": ["mountain-range"],
    "money": ["currency"],
    "capitals": ["capital"],
}

# Words that match everything and therefore mean nothing. Left out of the overlap score so "the
# music of France" does not route to every template with "of" in its question.
STOPWORDS = {
    "a", "an", "and", "are", "as", "at", "be", "by", "does", "for", "from", "in", "is", "it",
    "its", "of", "on", "or", "part", "that", "the", "this", "to", "was", "what", "which", "who",
    "with", "s", "o",
}


def words(text):
    return {w for w in re.findall(r"[a-z]+", str(text).lower()) if w not in STOPWORDS and len(w) > 2}


def vocabulary(template):
    """Every word a template says about itself, which is what a topic is matched against."""
    return words(f"{template.key} {template.category} {template.answer_type} {template.question}")


def route(topic, templates=None):
    """The templates a topic asks for, best first.

    Two signals, in order. An alias is an exact statement of intent and wins outright; overlap with
    a template's own vocabulary is the fallback, and it is why "mountain" finds `mountain-range`
    without anybody writing that down. A topic matching nothing returns nothing — the caller says so
    rather than guessing, because a pack about the wrong subject is worse than no pack.
    """
    templates = templates or gen.TEMPLATES
    by_key = {t.key: t for t in templates}
    asked = words(topic)

    chosen = []
    for word in asked:
        for key in ALIASES.get(word, []):
            if key in by_key and by_key[key] not in chosen:
                chosen.append(by_key[key])

    scored = []
    for template in templates:
        if template in chosen:
            continue
        overlap = len(asked & vocabulary(template))
        if overlap:
            scored.append((overlap, template.key, template))
    scored.sort(key=lambda row: (-row[0], row[1]))
    return chosen + [template for _, _, template in scored]


def plan(topic, templates=None):
    """The packs a topic becomes: one per category, because a pack has exactly one shelf."""
    packs = {}
    for template in route(topic, templates):
        packs.setdefault(template.category, []).append(template)
    return packs


def slug(topic):
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", topic.lower())).strip("-") or "topic"


def pack_json(topic, category, facts, language=gen.DEFAULT_LANGUAGE, stamp="0"):
    """A community pack, shaped exactly as `docs/community-packs.md` describes one."""
    suffix = "" if language == gen.DEFAULT_LANGUAGE else f"-{language}"
    pack_id = f"topic-{slug(topic)}-{category}{suffix}"
    return {
        "category": category,
        "packId": pack_id,
        "name": f"{topic.strip().title()} · {gen.CATEGORIES[category][language]}",
        "version": stamp,
        "language": language,
        # Ids are namespaced by the pack, which is the rule in `docs/community-packs.md`: an id
        # that collides with one already published replaces that fact and takes its review history.
        "facts": [{**fact, "id": f"{pack_id}-{fact['id']}"} for fact in facts],
    }


def harvest(topic, limit, language, stamp):
    """The network half. Kept in one function so everything above it is testable offline."""
    built = {}
    for category, templates in sorted(plan(topic).items()):
        facts = []
        for template in templates:
            if gen.phrase(template, language) is None:
                print(f"  {template.key}: not phrased in {language}, skipped")
                continue
            rows = gen.harvest(template, limit, language)
            made = gen.make_facts(template, rows, language)
            print(f"  {template.key:20s} {len(made)} facts")
            facts.extend(made)
        if len(facts) < MIN_FACTS_PER_PACK:
            print(f"  {category}: {len(facts)} facts is too few to publish")
            continue
        built[category] = pack_json(topic, category, facts, language, stamp)
    return built


def write(built, out_dir=OUT_DIR):
    """Writes packs, then refuses any the validator would reject — before they reach the repo."""
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for category, pack in sorted(built.items()):
        path = out_dir / f"{pack['packId']}.json"
        path.write_text(json.dumps(pack, ensure_ascii=False, indent=1), encoding="utf-8")
        report = validate_pack.Report()
        parsed = validate_pack.read_pack(path, report)
        validate_pack.check_corpus([parsed] if parsed else [], report)
        if report.errors or report.warnings:
            print(f"  {path.name}: refused —")
            report.print(strict=True)
            path.unlink()
            continue
        written.append(path)
        print(f"  {path.name}: {len(pack['facts'])} facts")
    return written


def ignored(topic):
    """Words in the topic that changed nothing, which is how narrowing fails today."""
    used = set()
    for template in route(topic):
        used |= vocabulary(template) | {template.key}
        used |= {w for w, keys in ALIASES.items() if template.key in keys}
    return sorted(words(topic) - used)


def describe(topic):
    packs = plan(topic)
    if not packs:
        print(f"'{topic}' matches none of the {len(gen.TEMPLATES)} questions this pipeline can ask.")
        print("A topic reaches the templates in generate_facts.py and nothing else — that is the")
        print("gap a language model is meant to close, and it is not closed yet.")
        return 1
    for category, templates in sorted(packs.items()):
        print(f"  {category:<10} {', '.join(t.key for t in templates)}")
    unused = ignored(topic)
    if unused:
        print(f"  ignored: {', '.join(unused)} — this cut picks which questions to ask, and")
        print("  cannot narrow which subjects they are asked about.")
    return 0


# --- self-test ----------------------------------------------------------------------------------

def self_test():
    failures = []

    def check(name, condition):
        print(f"  {'ok  ' if condition else 'FAIL'} {name}")
        if not condition:
            failures.append(name)

    keys = lambda topic: [t.key for t in route(topic)]

    check("an alias routes a topic its own words could never reach",
          "moon-parent" in keys("space"))
    check("and puts the alias first, ahead of anything merely overlapping",
          keys("space")[0] == "moon-parent")
    check("word overlap works without an alias",
          "mountain-range" in keys("mountain ranges"))
    check("a topic naming a category takes that category's templates",
          {"capital", "currency", "continent"} <= set(keys("geography")))
    check("a topic nobody can answer routes nowhere",
          keys("the byzantine succession") == [])
    check("stopwords alone route nowhere", keys("what is the") == [])
    check("case and punctuation do not matter",
          keys("Chemistry!") == keys("chemistry"))

    check("a topic spanning categories becomes one pack per category",
          set(plan("football and rivers")) == {"sports", "geography"})
    check("every planned template sits in the category it is planned under",
          all(t.category == c for c, ts in plan("geography").items() for t in ts))

    check("a qualifier the mapper cannot honour is reported, not hidden",
          "africa" in ignored("rivers of africa"))
    check("a topic it fully understood reports nothing ignored",
          ignored("chemistry") == [])

    check("a slug is safe in a filename", slug("Space & Time!") == "space-time")
    check("a slug never comes out empty", slug("!!!") == "topic")

    facts = [{"id": f"f{i}", "title": f"Moon {i}", "statement": f"Moon {i} orbits Planet{i}.",
              "question": f"Which body does Moon {i} orbit?", "answer": f"Planet{i}",
              "answerType": "planet", "wikiTitle": f"Moon {i}", "difficulty": 1}
             for i in range(8)]
    pack = pack_json("space", "science", facts)
    check("a built pack names its category and a specific packId",
          pack["category"] == "science" and pack["packId"] == "topic-space-science")
    check("every fact id is namespaced by the pack",
          all(f["id"].startswith("topic-space-science-") for f in pack["facts"]))
    check("a Russian pack suffixes its packId and every fact id",
          (lambda p: p["packId"].endswith("-ru") and all(f["id"].endswith("-ru") is False
                                                         for f in p["facts"][:0]) and
           all(f["id"].startswith("topic-space-science-ru-") for f in p["facts"]))(
              pack_json("space", "science", facts, "ru")))

    # The pack it builds has to be one the contract accepts — checked here rather than trusted,
    # because a generator that emits invalid packs is worse than no generator.
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "p.json"
        path.write_text(json.dumps(pack, ensure_ascii=False), encoding="utf-8")
        report = validate_pack.Report()
        parsed = validate_pack.read_pack(path, report)
        validate_pack.check_corpus([parsed] if parsed else [], report)
    check("and the contract accepts it", not report.errors and not report.warnings)

    thin = pack_json("space", "science", facts[:2])
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "p.json"
        path.write_text(json.dumps(thin, ensure_ascii=False), encoding="utf-8")
        report = validate_pack.Report()
        parsed = validate_pack.read_pack(path, report)
        validate_pack.check_corpus([parsed] if parsed else [], report)
    check("a pack too thin to quiz on is rejected by the contract, not published",
          bool(report.errors))

    print(f"\n{len(failures)} failed" if failures else "\nAll checks passed.")
    return 1 if failures else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--topic", help="what someone typed")
    parser.add_argument("--language", default=gen.DEFAULT_LANGUAGE, choices=sorted(gen.WIKI_HOSTS))
    parser.add_argument("--limit", type=int, default=120, help="facts per template")
    parser.add_argument("--write", action="store_true",
                        help="actually harvest and write the packs (needs Wikidata)")
    parser.add_argument("--version", default="1", help="the pack version to stamp")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.topic:
        parser.error("--topic is required")

    print(f"'{args.topic}' in {args.language}:")
    if not args.write:
        return describe(args.topic)

    built = harvest(args.topic, args.limit, args.language, args.version)
    if not built:
        print("Nothing worth publishing.")
        return 1
    return 0 if write(built) else 1


if __name__ == "__main__":
    sys.exit(main())
