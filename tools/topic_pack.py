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

**`--llm` is that gap closed.** `topic_llm.py` asks a model for the same two decisions and returns
them through five gates, and the plumbing here does not change at all — which is the payoff of
having built it this way round. The deterministic mapper stays as the default and as the fallback,
because it needs no key, no network and no review, and a topic it already answers well should not
cost an API call to answer again.

    python3 tools/topic_pack.py --topic "space"                  # what it would harvest
    python3 tools/topic_pack.py --topic "space" --write          # harvest it (needs Wikidata)
    python3 tools/topic_pack.py --topic "rivers of africa" --llm # ask the model to narrow it
    python3 tools/topic_pack.py --self-test
"""

import argparse
import collections
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import generate_facts as gen                                          # noqa: E402
import topic_llm                                                      # noqa: E402
import validate_pack                                                  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "packs" / "community"

# A pack that cannot furnish four options is not a pack. Same floor the rest of the pipeline uses,
# stated again here because this is the number that decides whether a topic is answerable at all.
MIN_FACTS_PER_PACK = 12

# Below this, a narrowing clause did not narrow the topic — it emptied it, which almost always
# means it constrained the wrong property. Four is the number of options a question has, so a
# template contributing fewer than four cannot even furnish one question's worth of them.
MIN_NARROWED_FACTS = 4

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


# What both mappers hand back, so everything downstream reads one shape: the template to ask, the
# SPARQL fragment narrowing it (empty from the deterministic mapper, which cannot narrow), and the
# model's own account of why. The `why` is not decoration — it is what a reviewer reads in the
# cache diff to decide whether a topic was understood.
Entry = collections.namedtuple("Entry", "template clause why")


def entries(topic, use_llm=False, templates=None, **kw):
    """{category: [Entry]} — the plan, from whichever mapper was asked for.

    The two mappers are interchangeable here on purpose. `route` is the default because it costs
    nothing and its answers need no review; the model is opt-in because its answers do. A topic the
    table already handles should not cost an API call to handle again.
    """
    by_key = {t.key: t for t in (templates or gen.TEMPLATES)}
    packs = {}

    if use_llm:
        proposals, note, cached = topic_llm.plan_for(topic, templates=templates, **kw)
        if note:
            print(f"  model: {note}")
        if cached:
            print("  (from tools/topic_cache.json — asked once, answered forever)")
        for proposal in proposals:
            template = by_key[proposal["key"]]
            packs.setdefault(template.category, []).append(
                Entry(template, proposal.get("narrow", ""), proposal.get("why", "")))
        return packs

    for category, chosen in plan(topic, templates).items():
        packs[category] = [Entry(t, "", "") for t in chosen]
    return packs


def slug(topic):
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", topic.lower())).strip("-") or "topic"


def pack_json(topic, category, facts, language=gen.DEFAULT_LANGUAGE, stamp="0"):
    """A community pack, shaped exactly as `docs/community-packs.md` describes one."""
    suffix = "" if language == gen.DEFAULT_LANGUAGE else f"-{language}"
    namespace = f"topic-{slug(topic)}-{category}"
    return {
        "category": category,
        "packId": f"{namespace}{suffix}",
        "name": f"{topic.strip().title()} · {gen.CATEGORIES[category][language]}",
        "version": stamp,
        "language": language,
        # Ids are namespaced by the pack, which is the rule in `docs/community-packs.md`: an id
        # that collides with one already published replaces that fact and takes its review history.
        #
        # The language tag goes *last* on a fact id, after the namespace, because that is the one
        # place the rule is strict — `make_facts` suffixes, the hand-authored packs suffix, and the
        # validator checks for it there. Namespacing with an id that already ended in the tag would
        # bury it in the middle and produce `topic-space-science-ru-f0`, which reads translated and
        # would be refused.
        "facts": [{**fact, "id": f"{namespace}-{fact['id']}{suffix}"} for fact in facts],
    }


def harvest(topic, limit, language, stamp, use_llm=False, **kw):
    """The network half. Kept in one function so everything above it is testable offline."""

    def run(template, clause):
        """One narrowed harvest: the facts it yielded, how many subjects it matched, and a note.

        The subject count is reported separately from the fact count because they fail for
        different reasons and the difference is the diagnosis. Zero subjects means the clause
        matched nothing — a coverage problem, worth asking the model to route around. Plenty of
        subjects and few facts means the labels or articles were missing in this language, which
        no re-narrowing can fix.
        """
        asked = topic_llm.narrowed(template, clause)
        # `gen.harvest` answers with the rows *and* how it got them — which floor it settled on,
        # or that every floor timed out. An earlier version of this line took the pair for the
        # rows and passed a tuple to `make_facts`; it crashed on the first row and nobody saw it,
        # because reaching this line at all needs Wikidata and no environment this was written in
        # has a route to it. The self-test now runs the whole function against a fake endpoint,
        # which is the only way a network-only path gets checked offline.
        rows, note = gen.harvest(asked, limit, language)
        made = gen.make_facts(asked, rows, language)

        # The same drop `generate_facts.py` makes over the whole library, made here too. A
        # Wikidata label carrying its own disambiguation — "Pietà (Michelangelo)" — produces a
        # question printing its own answer, and `validate_pack` treats one of those as an error
        # over the entire pack. An unlucky label should cost its own fact, not the topic.
        leaking = [f for f in made if gen.leaks_answer(f)]
        if leaking:
            made = [f for f in made if not gen.leaks_answer(f)]
            print(f"  {template.key:20s} dropped {len(leaking)} that gave themselves away")
        return made, len({r["s"]["value"] for r in rows if r.get("s")}), note

    built = {}
    for category, plans in sorted(entries(topic, use_llm, **kw).items()):
        facts = []
        for template, clause, _why in plans:
            if gen.phrase(template, language) is None:
                print(f"  {template.key}: not phrased in {language}, skipped")
                continue

            made, matched, note = run(template, clause)

            # The narrowing gate the model's own output cannot check for itself, and the one that
            # taught this pipeline its most useful lesson. "Rivers of Africa" was narrowed to
            # `?s wdt:P30 wd:Q15 .` — correct English, real ids, both verified against Wikidata —
            # and it matched **nothing**, because almost no river carries P30. A clause can be
            # perfectly right about meaning and still be wrong about coverage, and no gate that
            # reads the clause can tell.
            #
            # So the endpoint answers the question the gates cannot: it is asked, and if the
            # answer is empty the model is told exactly that and asked to route around it. What
            # never happens is widening back to the un-narrowed query, because a pack called
            # "Rivers of Africa" full of European rivers is the bug this whole path exists to fix.
            if clause and len(made) < MIN_NARROWED_FACTS:
                print(f"  {template.key:20s} narrowing matched {matched} subjects, "
                      f"{len(made)} usable — asking for another  [{note}]")
                # Not just "that did not work" but "here is how these things are actually
                # connected". The model guessed P361 for Star Wars music and got one fact; asking
                # Wikidata which properties really link the subjects to wd:Q462 turns the second
                # attempt from another guess into a choice from a list.
                hint = "".join(
                    topic_llm.links_for(template.where, entity, ask=kw.get("ask"))
                    for entity in topic_llm.clause_ids(clause) if entity.startswith("Q"))
                better = topic_llm.retry(
                    topic, template.key, clause,
                    f"it matched {matched} subjects, too few to build a pack from — the property "
                    f"is not populated on the subjects this template selects" + hint,
                    **retry_args(kw)) if use_llm else None
                if better and better["narrow"] != clause:
                    clause = better["narrow"]
                    print(f"  {template.key:20s} retrying with {clause}")
                    made, matched, note = run(template, clause)

            if clause and len(made) < MIN_NARROWED_FACTS:
                print(f"  {template.key:20s} narrowing left {len(made)} facts — dropped, "
                      f"not widened back  [{note}]")
                continue

            print(f"  {template.key:20s} {len(made)} facts  [{note}]"
                  + ("  (narrowed)" if clause else ""))
            facts.extend(made)
        if len(facts) < MIN_FACTS_PER_PACK:
            print(f"  {category}: {len(facts)} facts is too few to publish")
            continue
        built[category] = pack_json(topic, category, facts, language, stamp)
    return built


def retry_args(kw):
    """The mapper arguments a retry needs, dropping the ones only the first ask takes."""
    return {k: v for k, v in kw.items() if k in {"provider", "templates", "ask", "model",
                                                 "cache_path", "use_cache", "fetch"}}


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


def describe(topic, use_llm=False, **kw):
    packs = entries(topic, use_llm, **kw)
    if not packs:
        print(f"'{topic}' matches none of the {len(gen.TEMPLATES)} questions this pipeline can ask.")
        if use_llm:
            print("The model read the whole catalogue and found nothing that fits, which is a")
            print("real answer: the gap is a template nobody has written, not a mapping nobody")
            print("has made. Adding one is a change to generate_facts.py.")
        else:
            print("A topic reaches the templates in generate_facts.py and nothing else. Try --llm,")
            print("which reads the same catalogue and can also narrow what it picks.")
        return 1

    for category, plans in sorted(packs.items()):
        print(f"  {category:<10} {', '.join(e.template.key for e in plans)}")
        for template, clause, why in plans:
            if clause:
                print(f"  {'':<10} {template.key}: {clause}")
                if why:
                    print(f"  {'':<10} {'':<{len(template.key)}}  {why}")

    # Only the deterministic mapper has words it could not honour. The model's whole advantage is
    # that a qualifier becomes a clause instead of being dropped, so reporting "ignored: africa"
    # after it narrowed by Africa would be the tool lying about its own output.
    if not use_llm:
        unused = ignored(topic)
        if unused:
            print(f"  ignored: {', '.join(unused)} — this cut picks which questions to ask, and")
            print("  cannot narrow which subjects they are asked about. --llm can.")
    elif not any(e.clause for plans in packs.values() for e in plans):
        print("  not narrowed — the model judged the topic broad enough to ask whole.")
    return 0


# --- self-test ----------------------------------------------------------------------------------

def self_test():
    failures = []

    def check(name, condition):
        print(f"  {'ok  ' if condition else 'FAIL'} {name}")
        if not condition:
            failures.append(name)

    keys = lambda topic: [t.key for t in route(topic)]

    def validate_report(pack):
        """What `write` would say about a pack, without writing it into the repository."""
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "p.json"
            path.write_text(json.dumps(pack, ensure_ascii=False), encoding="utf-8")
            report = validate_pack.Report()
            parsed = validate_pack.read_pack(path, report)
            validate_pack.check_corpus([parsed] if parsed else [], report)
        return report

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
    ru_pack = pack_json("space", "science", facts, "ru")
    check("a Russian pack names its language in the packId",
          ru_pack["packId"] == "topic-space-science-ru")
    check("and every Russian fact id ends with the tag, where the rule is strict",
          all(f["id"].endswith("-ru") for f in ru_pack["facts"]))
    check("without losing the namespace that keeps it off another pack's ids",
          all(f["id"].startswith("topic-space-science-") for f in ru_pack["facts"]))

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

    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "ru.json"
        path.write_text(json.dumps(ru_pack, ensure_ascii=False), encoding="utf-8")
        report = validate_pack.Report()
        parsed = validate_pack.read_pack(path, report)
        validate_pack.check_corpus([parsed] if parsed else [], report)
    check("and the contract accepts the Russian one too",
          not report.errors and not report.warnings)

    # --- the two mappers, and the path that only exists over the network -----------------
    #
    # Everything below runs `harvest` end to end against a fake endpoint. That matters more here
    # than it looks: this function is the one part of the tool that cannot be exercised anywhere
    # it is developed, because it needs a route to query.wikidata.org and no sandbox has one. It
    # shipped with `rows = gen.harvest(...)` taking a `(rows, note)` pair for the rows, which
    # would have crashed on the first row of the first real run. A fake endpoint is what turns
    # "untested until CI" into "tested here".

    def fake_rows(count, answers=None, prefix="Thing"):
        return {"results": {"bindings": [
            {"s": {"value": f"http://www.wikidata.org/entity/Q{100 + i}"},
             "sLabel": {"value": f"{prefix} {i}"},
             "oLabel": {"value": (answers or ["Ocean A", "Ocean B", "Ocean C", "Ocean D"])[i % 4]},
             "sl": {"value": "45"},
             "article": {"value": f"https://en.wikipedia.org/wiki/{prefix}_{i}"}}
            for i in range(count)]}}

    class FakeEndpoint:
        """Stands in for Wikidata, and records which queries it was actually asked."""

        def __init__(self, count=20, narrowed_count=None):
            self.count, self.narrowed_count, self.queries = count, narrowed_count, []

        def __call__(self, query, timeout=65):
            self.queries.append(query)
            narrowed = "wd:Q15" in query
            n = self.narrowed_count if (narrowed and self.narrowed_count is not None) else self.count
            return fake_rows(n)

    def with_endpoint(endpoint, fn, *a, **kw):
        real = gen.sparql
        gen.sparql = endpoint
        try:
            return fn(*a, **kw)
        finally:
            gen.sparql = real

    plain = entries("rivers")
    check("the deterministic mapper still answers, and narrows nothing",
          [e.clause for e in plain["geography"]] == [""])

    GOOD = {"key": "river-mouth", "narrow": "?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .",
            "why": "rivers in African countries",
            "entities": [{"id": "P17", "label": "country"},
                         {"id": "P30", "label": "continent"},
                         {"id": "Q15", "label": "Africa"}]}

    # The clause that actually shipped and matched nothing: right about meaning, wrong about
    # coverage, because almost no river carries P30 directly.
    SPARSE = {"key": "river-mouth", "narrow": "?s wdt:P30 wd:Q15 .",
              "why": "rivers on the continent of Africa",
              "entities": [{"id": "P30", "label": "continent"},
                           {"id": "Q15", "label": "Africa"}]}

    def fake_model(topic, templates=None, model=None, key=None, prompt=None):
        return {"templates": [GOOD], "note": ""}, "fake-1"

    def fake_labels(query):
        known = {"P17": "country", "P30": "continent", "Q15": "Africa"}
        return {"results": {"bindings": [
            {"item": {"value": f"http://www.wikidata.org/entity/{i}"},
             "label": {"value": known[i]}}
            for i in re.findall(r"wd:([QP][0-9]+)", query) if i in known]}}

    topic_llm.PROVIDERS["fake"] = fake_model
    import tempfile
    tmpdir = tempfile.TemporaryDirectory()
    cache = Path(tmpdir.name) / "cache.json"
    llm_kw = dict(provider="fake", ask=fake_labels, cache_path=cache)

    narrow = entries("rivers of africa", use_llm=True, **llm_kw)
    check("the model mapper reaches the same template",
          [e.template.key for e in narrow["geography"]] == ["river-mouth"])
    check("and narrows it, which is the whole difference between the two",
          narrow["geography"][0].clause == "?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .")

    endpoint = FakeEndpoint(count=20)
    built = with_endpoint(endpoint, harvest, "rivers of africa", 50, "en", "1",
                          use_llm=True, **llm_kw)
    check("a narrowed harvest builds a pack", "geography" in built)
    check("and the narrowing actually reached the endpoint, rather than being planned and dropped",
          any("wd:Q15" in q for q in endpoint.queries))
    check("the facts it built are the shape the parser reads",
          all({"id", "question", "answer", "answerType"} <= set(f)
              for f in built["geography"]["facts"]))
    check("and the pack the contract would accept",
          not validate_report(built["geography"]).errors)

    # The gate that matters most, because failing it silently is the bug this path exists to fix.
    empty = FakeEndpoint(count=20, narrowed_count=2)
    thin_build = with_endpoint(empty, harvest, "rivers of africa", 50, "en", "1",
                               use_llm=True, **llm_kw)
    check("a narrowing that empties the template publishes nothing", thin_build == {})
    check("and never widens back to the un-narrowed query",
          all("wd:Q15" in q for q in empty.queries))

    unnarrowed = FakeEndpoint(count=20)
    with_endpoint(unnarrowed, harvest, "rivers", 50, "en", "1")
    check("the deterministic path harvests too, and asks for no narrowing",
          unnarrowed.queries and not any("wd:Q15" in q for q in unnarrowed.queries))

    # --- the retry, which is the lesson the first real run taught -------------------------
    #
    # A clause can be right about meaning and wrong about coverage. `?s wdt:P30 wd:Q15 .` is
    # exactly "on the continent of Africa", both ids verify against Wikidata, and it matched zero
    # rivers because almost none carry P30. No gate that reads the clause can see that; only the
    # endpoint can, so the endpoint is asked and the model is told what it said.

    class CoverageEndpoint:
        """Answers the sparse clause with nothing and the hop with plenty, as Wikidata did."""

        def __init__(self):
            self.queries = []

        def __call__(self, query, timeout=65):
            self.queries.append(query)
            return fake_rows(0 if "wdt:P30 wd:Q15" in query and "P17" not in query else 20)

    asked = []

    def sparse_then_better(topic, templates=None, model=None, key=None, prompt=None):
        asked.append(prompt)
        # The first ask gets no prompt of its own; the retry is the one that carries the failure.
        return ({"templates": [GOOD if prompt else SPARSE], "note": ""}, "fake-1")

    topic_llm.PROVIDERS["coverage"] = sparse_then_better
    with tempfile.TemporaryDirectory() as tmp2:
        cache2 = Path(tmp2) / "cache.json"
        endpoint = CoverageEndpoint()
        rescued = with_endpoint(endpoint, harvest, "rivers of africa", 50, "en", "1",
                                use_llm=True, provider="coverage", ask=fake_labels,
                                cache_path=cache2)
        check("a narrowing that matches nothing is retried, not dropped on the spot",
              len(asked) == 2 and asked[1] is not None)
        check("and the retry is told which clause failed",
              "?s wdt:P30 wd:Q15 ." in (asked[1] or ""))
        check("and carries whatever Wikidata could say about how they really connect",
              "matched 0 subjects" in (asked[1] or ""))
        check("the better clause is harvested and the pack is built after all",
              "geography" in rescued)
        check("the query that ran last is the retry's, not the one that matched nothing",
              "wdt:P17" in endpoint.queries[-1])
        check("and the un-narrowed query is never run, even after a failure",
              all("wd:Q15" in q for q in endpoint.queries))
        remembered = json.loads(cache2.read_text(encoding="utf-8"))
        clauses = [t["narrow"] for t in remembered["rivers of africa"]["templates"]]
        check("the cache records the clause that worked, not the one that did not",
              clauses == ["?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 ."])

    def no_better(topic, templates=None, model=None, key=None, prompt=None):
        return ({"templates": [] if prompt else [SPARSE], "note": "nothing else reaches it"},
                "fake-1")

    topic_llm.PROVIDERS["stuck"] = no_better
    with tempfile.TemporaryDirectory() as tmp3:
        stuck = with_endpoint(CoverageEndpoint(), harvest, "rivers of africa", 50, "en", "1",
                              use_llm=True, provider="stuck", ask=fake_labels,
                              cache_path=Path(tmp3) / "c.json")
        check("a model with nothing better publishes nothing, rather than widening back",
              stuck == {})
    topic_llm.PROVIDERS.pop("coverage"), topic_llm.PROVIDERS.pop("stuck")

    topic_llm.PROVIDERS.pop("fake")
    tmpdir.cleanup()

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
    parser.add_argument("--llm", action="store_true",
                        help="let a model pick the templates and narrow them (needs an API key)")
    parser.add_argument("--provider", default="gemini", choices=sorted(topic_llm.PROVIDERS),
                        help="which model answers, when --llm is on")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.topic:
        parser.error("--topic is required")

    mapper = {"use_llm": args.llm}
    if args.llm:
        mapper["provider"] = args.provider

    print(f"'{args.topic}' in {args.language}:")
    try:
        if not args.write:
            return describe(args.topic, **mapper)
        built = harvest(args.topic, args.limit, args.language, args.version, **mapper)
    except topic_llm.Refused as exc:
        # A refusal is the mapper working, not the tool breaking, so it prints as a sentence
        # rather than a traceback — and it never falls through to the deterministic mapper,
        # which would publish a pack about a topic the model had just declined to narrow.
        print(f"  refused: {exc}")
        return 1

    if not built:
        print("Nothing worth publishing.")
        return 1
    return 0 if write(built) else 1


if __name__ == "__main__":
    sys.exit(main())
