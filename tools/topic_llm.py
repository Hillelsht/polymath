#!/usr/bin/env python3
"""The one function `topic_pack.py` deferred: a language model that can *narrow* a topic.

`topic_pack.py` proved the whole path — topic in, validated pack out — with the dumbest possible
mapper, and named the two things that mapper cannot do. It cannot reach a template nobody wrote an
alias for, and it cannot narrow one: "rivers of Africa" harvests the same rivers as "rivers",
because narrowing means adding a constraint to a SPARQL query and that is a judgement call. This
file is that judgement, and nothing else. It picks from the templates that already exist and writes
a `WHERE` fragment; it never writes a fact, a question or an answer.

That boundary is the product decision, not a safety afterthought. The pitch is "about anything",
and the reason it can be trusted is that **every published fact is still a Wikidata claim**. A
model that phrased facts would be a model that could invent them, and one wrong fact in a teaching
app is worth more damage than ten topics that route nowhere.

So the model proposes and Wikidata disposes, in five gates that run before a single fact is
harvested:

  1. **A closed template list.** The key it picks must be one of the eighteen that exist. It cannot
     invent a nineteenth question shape, because inventing one means writing English.
  2. **A grammar, allowlisted.** The narrowing clause is triples and nothing else — no braces, no
     `SERVICE`, no property paths, no sub-selects. What is not spelled out here is refused, which
     is the only way round that stays safe when the thing writing the string is a model.
  3. **Well-formed ids.** `Q15` can exist; `Q0`, `Qafrica` and `P` cannot, and that costs no
     network to know.
  4. **The label check, which is the one that matters.** The model must state what it thinks each
     id *is* — `Q15` is "Africa" — and every claim is checked against the real label and aliases
     at `query.wikidata.org`. A hallucinated Q-number is not a subtle failure here: it silently
     harvests the wrong subject and publishes a pack about something nobody asked for. This gate
     turns that into a refusal.
  5. **A yield floor.** A narrowing that returns four facts is a narrowing that did not work, and
     falling back to the un-narrowed query would publish exactly the bug this file exists to fix.
     Too few is a refusal, reported by name.

Everything above the network line is testable offline, and `--self-test` runs it against a fake
model — including the hallucinations, because a gate nobody has watched reject something is not
known to reject anything.

Answers are cached in `tools/topic_cache.json`, committed. A topic asked once is free forever and,
more usefully, **reviewable in a diff**: what a model decided a topic meant is a content decision,
and content decisions in this repository are read before they ship.

    python3 tools/topic_llm.py --self-test               # offline, no key
    python3 tools/topic_llm.py --topic "rivers of Africa" --explain
    python3 tools/topic_llm.py --topic "rivers of Africa"    # asks the model, caches, verifies
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import generate_facts as gen                                          # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(__file__).resolve().parent / "topic_cache.json"

# A narrowing that yields less than this did not narrow, it emptied. Same floor topic_pack.py
# publishes at, restated here because this is where the decision to refuse is made.
MIN_ROWS = 12

# Four is enough for "rivers of countries in Africa" (two triples) with room to spare, and small
# enough that a runaway clause cannot become a join nobody can afford.
MAX_TRIPLES = 4


class Refused(Exception):
    """A proposal that failed a gate. The message is what to print — it names the gate."""


# --- The grammar ---------------------------------------------------------------------------
#
# Allowlisted, not blocklisted. A blocklist of dangerous SPARQL is a list of the injections
# somebody already thought of; this instead spells out the three token shapes a narrowing may
# contain and refuses everything else, so a construct nobody anticipated is refused by default
# rather than by having been guessed at.

VAR = r"\?[a-z][a-z0-9]{0,15}"
PREDICATE = r"(?:wdt|p|ps|pq):P[1-9][0-9]*"
ENTITY = r"wd:[QP][1-9][0-9]*"
TRIPLE = re.compile(rf"^({VAR})\s+({PREDICATE})\s+({ENTITY}|{VAR})$")

# The variables the surrounding query already binds. Anything else the clause introduces has to
# be used at least twice — once to bind it and once to constrain it — or it is a free variable
# multiplying the result set by everything in Wikidata.
BOUND = {"s", "o", "sl"}


def parse_clause(clause):
    """The triples in a narrowing clause, or [Refused] saying which rule it broke.

    Returns the list of triples parsed. Raises [Refused] otherwise. Deliberately strict about
    whitespace-and-periods rather than clever: a clause this file cannot read in one pass is a
    clause it should not be pasting into a query.
    """
    text = " ".join(str(clause or "").split())
    if not text:
        raise Refused("empty narrowing clause")
    if not text.endswith("."):
        raise Refused("a narrowing clause must end each triple with '.'")

    statements = [s.strip() for s in text.split(".") if s.strip()]
    if not statements:
        raise Refused("empty narrowing clause")
    if len(statements) > MAX_TRIPLES:
        raise Refused(f"{len(statements)} triples, more than the {MAX_TRIPLES} allowed")

    triples = []
    for statement in statements:
        match = TRIPLE.match(statement)
        if not match:
            raise Refused(f"'{statement}' is not a plain triple this file will run")
        triples.append(match.groups())

    counts = Counter(re.findall(rf"{VAR}", text))
    for var, seen in counts.items():
        if var.lstrip("?") not in BOUND and seen < 2:
            raise Refused(f"'{var}' appears once, so it constrains nothing and joins everything")
    if not ({"?s", "?o"} & set(counts)):
        raise Refused("a narrowing clause has to mention ?s or ?o, or it narrows nothing")
    return triples


def clause_ids(clause):
    """Every Wikidata id a clause names, in order, deduplicated."""
    seen = []
    for token in re.findall(r"(?:wdt|wd|ps|pq|p):([QP][0-9]+)", clause):
        if token not in seen:
            seen.append(token)
    return seen


# --- Verification ---------------------------------------------------------------------------

def normalise(label):
    return " ".join(str(label or "").lower().replace("’", "'").split())


def fetch_labels(ids, ask=None):
    """{id: {every label and alias it has in English}} — the ground truth the model is checked on.

    One query for all of them; the ids are a handful and this runs once per topic, ever.
    """
    if not ids:
        return {}
    values = " ".join(f"wd:{i}" for i in ids)
    query = f"""
    SELECT ?item ?label WHERE {{
      VALUES ?item {{ {values} }}
      {{ ?item rdfs:label ?label . FILTER(lang(?label) = "en") }}
      UNION
      {{ ?item skos:altLabel ?label . FILTER(lang(?label) = "en") }}
    }}
    """
    rows = (ask or gen.sparql)(query)["results"]["bindings"]
    found = {}
    for row in rows:
        qid = row["item"]["value"].rsplit("/", 1)[-1]
        found.setdefault(qid, set()).add(normalise(row["label"]["value"]))
    return found


def check_entities(entities, ask=None):
    """The label gate. Every id the model named must be the thing the model said it was.

    Aliases count — Wikidata's own label for Q15 is "Africa" but a model calling P17 "country of
    origin" instead of "country" is describing the right property, and refusing that would be
    refusing a correct answer for a spelling. What is refused is a model that says Q15 is "Asia",
    which is not a spelling difference but a different continent, and the pack it would build
    would be about the wrong half of the world.
    """
    claimed = {}
    for entry in entities or []:
        eid = str(entry.get("id", "")).strip()
        if not gen.WELL_FORMED.match(eid):
            raise Refused(f"'{eid}' is not a well-formed Wikidata id")
        claimed[eid] = str(entry.get("label", "")).strip()

    real = fetch_labels(sorted(claimed), ask=ask)
    for eid, label in sorted(claimed.items()):
        if eid not in real:
            raise Refused(f"{eid} does not exist on Wikidata (the model called it '{label}')")
        if normalise(label) not in real[eid]:
            example = sorted(real[eid])[:3]
            raise Refused(f"{eid} is not '{label}' — Wikidata calls it {', '.join(example)}")
    return claimed


def verify(proposal, templates=None, ask=None):
    """Every gate, in the order that spends the least before refusing.

    Returns the proposal, annotated. Raises [Refused] with the gate that stopped it. Gates 1-3
    cost nothing; gate 4 costs one query; the yield floor costs a real harvest and is checked by
    the caller, which is why it is not here.
    """
    by_key = {t.key: t for t in (templates or gen.TEMPLATES)}
    key = str(proposal.get("key", "")).strip()
    if key not in by_key:
        raise Refused(f"'{key}' is not one of the {len(by_key)} questions this pipeline can ask")

    clause = str(proposal.get("narrow", "")).strip()
    if not clause:
        # A model that declines to narrow is answering honestly — "chemistry" has nothing to
        # narrow by — and that is a plan, not a refusal.
        return {**proposal, "key": key, "narrow": "", "entities": []}

    parse_clause(clause)
    named = set(clause_ids(clause))
    claimed = check_entities(proposal.get("entities"), ask=ask)
    missing = named - set(claimed)
    if missing:
        raise Refused(f"the clause uses {', '.join(sorted(missing))} without saying what they are")
    return {**proposal, "key": key, "narrow": clause, "entities": proposal.get("entities", [])}


def narrowed(template, clause):
    """The template with the clause folded into its WHERE, leaving the original untouched."""
    import copy
    if not clause:
        return template
    clone = copy.copy(template)
    clone.where = f"{template.where.rstrip()} {clause.strip()}"
    return clone


# --- The model ------------------------------------------------------------------------------

def catalogue(templates=None):
    """What the model is allowed to choose from, in its own terms."""
    return [
        {"key": t.key, "category": t.category, "asks": t.question,
         "answer_is": t.answer_type, "where": " ".join(t.where.split())}
        for t in (templates or gen.TEMPLATES)
    ]


INSTRUCTIONS = """\
You map a topic somebody typed onto questions a fixed pipeline already knows how to ask.

You are given a catalogue of question templates. Each has a SPARQL `where` fragment that binds
?s (the thing asked about) and ?o (the answer). You do two things and nothing else:

1. Pick the templates a person typing this topic would expect to be quizzed on. Pick only what
   the topic actually asks for — a topic about rivers is not also about currencies. Prefer few
   and right over many and loose. If the topic asks for something the catalogue cannot ask,
   return no templates and explain what was missing in `note`.

2. For each template, optionally narrow it, so the topic's qualifier changes which subjects are
   harvested. This is the whole point: "rivers of Africa" must not return the same rivers as
   "rivers".

Rules for `narrow`, all enforced — a clause that breaks one is discarded and your whole answer
for that template with it:

  * Plain triples only, each ending with a period: `?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .`
  * Subjects and objects are `?s`, `?o`, `wd:Q...`, `wd:P...`, or a variable you introduce.
  * Predicates are `wdt:`, `p:`, `ps:` or `pq:` followed by a P-number.
  * At most 4 triples. No braces, no FILTER, no SERVICE, no OPTIONAL, no UNION, no VALUES,
    no property paths (`/`, `*`, `+`), no semicolons, no comments, no IRIs in angle brackets.
  * A variable you introduce must appear at least twice. `?s` and `?o` are already bound.
  * The clause is ANDed onto the template's existing `where`, so do not repeat what it says.
  * Leave `narrow` as "" when the topic needs no narrowing. An empty clause is a good answer for
    a broad topic; a wrong clause is not.

In `entities`, list every Q-number and P-number your clause uses, with the English label you
believe it has. Every one is checked against Wikidata before anything runs. Say what you
actually believe — a guessed id whose label does not match is thrown out, and so is the clause.

Return JSON only.
"""


def prompt_for(topic, templates=None):
    return (f"{INSTRUCTIONS}\nTopic: {json.dumps(topic)}\n\nCatalogue:\n"
            f"{json.dumps(catalogue(templates), ensure_ascii=False, indent=1)}\n")


SCHEMA = {
    "type": "object",
    "properties": {
        "templates": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "key": {"type": "string"},
                    "narrow": {"type": "string"},
                    "why": {"type": "string"},
                    "entities": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {"id": {"type": "string"},
                                           "label": {"type": "string"}},
                            "required": ["id", "label"],
                        },
                    },
                },
                "required": ["key", "narrow", "why", "entities"],
            },
        },
        "note": {"type": "string"},
    },
    "required": ["templates", "note"],
}


def post(url, payload, headers, timeout=90):
    request = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")[:400]
        raise Refused(f"the model API answered {error.code}: {detail}") from error


def loads(text):
    """The model's JSON, whether or not it arrived wrapped in a code fence."""
    text = str(text or "").strip()
    fenced = re.match(r"^```(?:json)?\s*(.*?)\s*```$", text, re.S)
    if fenced:
        text = fenced.group(1)
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise Refused(f"the model did not return JSON: {exc}") from exc


def ask_gemini(topic, templates=None, model=None, key=None):
    model = model or os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")
    key = key or os.environ.get("GEMINI_API_KEY", "")
    if not key:
        raise Refused("GEMINI_API_KEY is not set — this step only runs where the secret is")
    body = post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        {"contents": [{"parts": [{"text": prompt_for(topic, templates)}]}],
         "generationConfig": {"temperature": 0, "responseMimeType": "application/json",
                              "responseSchema": SCHEMA}},
        {"x-goog-api-key": key})
    try:
        text = body["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError) as exc:
        raise Refused(f"the model returned no answer: {json.dumps(body)[:300]}") from exc
    return loads(text), model


def ask_anthropic(topic, templates=None, model=None, key=None):
    """The same call against Claude, because the provider is a setting and not an architecture.

    Kept deliberately: the mapping step is small and the whole point of the gates above is that
    they do not care which model wrote the string they are checking.
    """
    model = model or os.environ.get("ANTHROPIC_MODEL", "claude-sonnet-4-5")
    key = key or os.environ.get("ANTHROPIC_API_KEY", "")
    if not key:
        raise Refused("ANTHROPIC_API_KEY is not set")
    body = post(
        "https://api.anthropic.com/v1/messages",
        {"model": model, "max_tokens": 2000, "temperature": 0,
         "system": "Return a single JSON object and nothing else.",
         "messages": [{"role": "user", "content": prompt_for(topic, templates)}]},
        {"x-api-key": key, "anthropic-version": "2023-06-01"})
    try:
        text = body["content"][0]["text"]
    except (KeyError, IndexError) as exc:
        raise Refused(f"the model returned no answer: {json.dumps(body)[:300]}") from exc
    return loads(text), model


PROVIDERS = {"gemini": ask_gemini, "anthropic": ask_anthropic}


# --- The cache ------------------------------------------------------------------------------

def load_cache(path=CACHE):
    if not Path(path).is_file():
        return {}
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save_cache(cache, path=CACHE):
    Path(path).write_text(
        json.dumps(cache, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")


def cache_key(topic):
    return " ".join(str(topic).lower().split())


def plan_for(topic, provider="gemini", templates=None, ask=None, model=None, use_cache=True,
             cache_path=CACHE):
    """The verified plan for a topic: cached if it has been asked, asked and verified if not.

    Returns (plan, note, cached). [plan] is a list of verified proposals; a topic the model could
    not serve returns an empty list and a note saying why, which is an answer rather than a
    failure — the same honesty `topic_pack.py` prints when a topic routes nowhere.
    """
    cache = load_cache(cache_path) if use_cache else {}
    slot = cache.get(cache_key(topic))
    if slot:
        return slot["templates"], slot.get("note", ""), True

    raw, used_model = PROVIDERS[provider](topic, templates)
    verified, refusals = [], []
    for proposal in raw.get("templates") or []:
        try:
            verified.append(verify(proposal, templates, ask=ask))
        except Refused as exc:
            refusals.append(f"{proposal.get('key', '?')}: {exc}")

    note = str(raw.get("note", "")).strip()
    for refusal in refusals:
        print(f"  refused — {refusal}")
    if refusals:
        note = "; ".join([note] + refusals) if note else "; ".join(refusals)

    cache[cache_key(topic)] = {"topic": topic, "provider": provider, "model": used_model,
                               "templates": verified, "note": note}
    if use_cache:
        save_cache(cache, cache_path)
    return verified, note, False


# --- self-test ------------------------------------------------------------------------------

def self_test():
    failures = []

    def check(name, condition):
        print(f"  {'ok  ' if condition else 'FAIL'} {name}")
        if not condition:
            failures.append(name)

    def refused(fn, *a, **kw):
        """Did the gate refuse, and with what? Returns the message, or None if it let it through."""
        try:
            fn(*a, **kw)
            return None
        except Refused as exc:
            return str(exc)

    # --- the grammar ---------------------------------------------------------------------
    check("a plain narrowing parses",
          len(parse_clause("?s wdt:P30 wd:Q15 .")) == 1)
    check("so does one that hops through a variable",
          len(parse_clause("?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .")) == 2)
    check("whitespace and newlines do not matter",
          len(parse_clause("  ?s   wdt:P17\n?c .\n ?c wdt:P30 wd:Q15 . ")) == 2)

    check("a service call is refused", refused(parse_clause,
          "?s wdt:P17 ?c . SERVICE wikibase:label { ?c rdfs:label ?l }"))
    check("so are braces of any kind", refused(parse_clause, "?s wdt:P31 wd:Q4022 { }"))
    check("so is a FILTER, which this cut does not run",
          refused(parse_clause, "?s wdt:P571 ?d . FILTER(YEAR(?d) < 1900) ."))
    check("so is a property path, which is how one triple becomes a whole subtree",
          refused(parse_clause, "?s wdt:P31/wdt:P279* wd:Q4022 ."))
    check("so is a semicolon list, because the grammar reads full triples only",
          refused(parse_clause, "?s wdt:P31 wd:Q4022 ; wdt:P30 wd:Q15 ."))
    check("so is an update disguised as a clause",
          refused(parse_clause, "?s wdt:P30 wd:Q15 . DELETE WHERE { ?x ?y ?z } ."))
    check("so is an angle-bracket IRI", refused(parse_clause, "?s <http://example.com/p> ?o ."))
    check("a missing period is refused rather than guessed at",
          refused(parse_clause, "?s wdt:P30 wd:Q15"))
    check("a free variable is refused, because it joins against everything",
          refused(parse_clause, "?s wdt:P30 ?anything ."))
    check("a clause that constrains neither ?s nor ?o is refused",
          refused(parse_clause, "?a wdt:P30 ?b . ?b wdt:P17 ?a ."))
    check("too many triples is refused",
          refused(parse_clause, " . ".join(["?s wdt:P30 wd:Q15"] * 5) + " ."))
    check("an empty clause is not a clause", refused(parse_clause, "   "))

    check("ids are read out of a clause in order",
          clause_ids("?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .") == ["P17", "P30", "Q15"])

    # --- the label gate ------------------------------------------------------------------
    #
    # A fake Wikidata, so the gate is watched refusing something rather than assumed to.
    WORLD = {"Q15": {"africa"}, "P30": {"continent", "part of the continent"},
             "P17": {"country"}, "Q48": {"asia"}}

    def fake_ask(query):
        wanted = re.findall(r"wd:([QP][0-9]+)", query)
        return {"results": {"bindings": [
            {"item": {"value": f"http://www.wikidata.org/entity/{i}"}, "label": {"value": lab}}
            for i in wanted for lab in WORLD.get(i, ())]}}

    check("a truthful id passes",
          check_entities([{"id": "Q15", "label": "Africa"}], ask=fake_ask) == {"Q15": "Africa"})
    check("case and spacing in the label do not matter",
          bool(check_entities([{"id": "Q15", "label": "  africa "}], ask=fake_ask)))
    check("an alias counts, so a right property with a loose name is not refused for spelling",
          bool(check_entities([{"id": "P30", "label": "part of the continent"}], ask=fake_ask)))
    check("a hallucinated meaning is refused — the whole reason this gate exists",
          "not 'Asia'" in (refused(check_entities, [{"id": "Q15", "label": "Asia"}],
                                   ask=fake_ask) or ""))
    check("an id that does not exist is refused",
          "does not exist" in (refused(check_entities, [{"id": "Q99999999", "label": "Atlantis"}],
                                       ask=fake_ask) or ""))
    check("an id that could not exist is refused with no network at all",
          "well-formed" in (refused(check_entities, [{"id": "Qafrica", "label": "Africa"}]) or ""))

    # --- the whole proposal --------------------------------------------------------------
    good = {"key": "river-mouth", "narrow": "?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .",
            "why": "rivers in African countries",
            "entities": [{"id": "P17", "label": "country"}, {"id": "P30", "label": "continent"},
                         {"id": "Q15", "label": "Africa"}]}
    check("a good proposal verifies", verify(good, ask=fake_ask)["narrow"].startswith("?s wdt:P17"))
    check("an invented template key is refused",
          "not one of" in (refused(verify, {**good, "key": "byzantine-succession"},
                                   ask=fake_ask) or ""))
    check("a clause naming an id it never declared is refused",
          "without saying what they are" in
          (refused(verify, {**good, "entities": [{"id": "Q15", "label": "Africa"}]},
                   ask=fake_ask) or ""))
    check("declining to narrow is a plan, not a refusal",
          verify({"key": "element-symbol", "narrow": "", "entities": []},
                 ask=fake_ask)["narrow"] == "")

    # --- folding it into the query -------------------------------------------------------
    river = next(t for t in gen.TEMPLATES if t.key == "river-mouth")
    tight = narrowed(river, "?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .")
    check("narrowing adds to the template's WHERE", len(tight.where) > len(river.where))
    check("and keeps everything the template already said", river.where.strip() in tight.where)
    check("and leaves the original template alone, which is shared module state",
          "Q15" not in river.where)
    check("the narrowed template still builds a query the pipeline would run",
          "wd:Q15" in gen.build_query(tight, 30, 10, "en"))
    check("every id the narrowed query names is well-formed",
          not gen.malformed_ids(tight))
    check("not narrowing returns the very same template", narrowed(river, "") is river)

    # --- the model contract --------------------------------------------------------------
    check("the catalogue offers every template and no more",
          {row["key"] for row in catalogue()} == {t.key for t in gen.TEMPLATES})
    check("the catalogue tells the model what ?s and ?o bind to",
          all(row["where"] for row in catalogue()))
    check("the prompt carries the topic verbatim",
          "rivers of Africa" in prompt_for("rivers of Africa"))
    check("the prompt names the grammar the gates enforce",
          "at most 4 triples" in prompt_for("x").lower())

    check("JSON in a code fence is still JSON", loads('```json\n{"a": 1}\n```') == {"a": 1})
    check("bare JSON is still JSON", loads('{"a": 1}') == {"a": 1})
    check("prose instead of JSON is refused", refused(loads, "I think the topic is about rivers"))

    # --- the cache -----------------------------------------------------------------------
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "cache.json"
        calls = []

        def fake_model(topic, templates=None, model=None, key=None):
            calls.append(topic)
            return {"templates": [good], "note": ""}, "fake-1"

        PROVIDERS["fake"] = fake_model
        first = plan_for("rivers of Africa", "fake", ask=fake_ask, cache_path=path)
        second = plan_for("Rivers  of  AFRICA", "fake", ask=fake_ask, cache_path=path)
        check("a topic is asked once", len(calls) == 1)
        check("and answered from cache the second time, however it was typed", second[2] is True)
        check("with the same plan", first[0] == second[0])
        check("and the cache is written where a reviewer will see it in a diff", path.is_file())

        calls.clear()

        def lying_model(topic, templates=None, model=None, key=None):
            return {"templates": [{**good, "entities": [{"id": "Q15", "label": "Asia"}]}],
                    "note": "narrowed to Asia"}, "fake-1"

        PROVIDERS["liar"] = lying_model
        plan, note, _ = plan_for("rivers of asia", "liar", ask=fake_ask, cache_path=path)
        check("a proposal that fails a gate is dropped, not published", plan == [])
        check("and the reason is recorded next to the topic, not just printed",
              "Q15 is not 'Asia'" in note)
        cached = json.loads(path.read_text(encoding="utf-8"))
        check("a topic that yielded nothing is still cached, so it is not re-asked forever",
              cache_key("rivers of asia") in cached)
        PROVIDERS.pop("fake"), PROVIDERS.pop("liar")

    print(f"\n{len(failures)} failed" if failures else "\nAll checks passed.")
    return 1 if failures else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--topic")
    parser.add_argument("--provider", default="gemini", choices=sorted(PROVIDERS))
    parser.add_argument("--model", help="overrides GEMINI_MODEL / ANTHROPIC_MODEL")
    parser.add_argument("--explain", action="store_true",
                        help="print the prompt the model would be sent, and ask nothing")
    parser.add_argument("--no-cache", action="store_true", help="ask again and do not record it")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.topic:
        parser.error("--topic is required")
    if args.explain:
        print(prompt_for(args.topic))
        return 0

    try:
        plan, note, cached = plan_for(args.topic, args.provider, model=args.model,
                                      use_cache=not args.no_cache)
    except Refused as exc:
        print(f"{args.topic}: {exc}")
        return 1

    print(f"'{args.topic}'{' (cached)' if cached else ''}:")
    for proposal in plan:
        clause = proposal["narrow"] or "(not narrowed)"
        print(f"  {proposal['key']:20s} {clause}")
        if proposal.get("why"):
            print(f"  {'':20s} {proposal['why']}")
    if note:
        print(f"  note: {note}")
    if not plan:
        print("  nothing this pipeline can ask.")
    return 0 if plan else 1


if __name__ == "__main__":
    sys.exit(main())
