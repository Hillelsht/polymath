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

What a real model actually does with this, from `probe.yml`'s first run — recorded here rather than
remembered, the way the other two probes record theirs:

    rivers of Africa    river-mouth     ?s wdt:P30 wd:Q15 .
    chemistry           element-symbol  (not narrowed)
                        discoverer      ?s wdt:P31 wd:Q11344 .
                        named-after     ?s wdt:P31 wd:Q11344 .
    films by Japanese   film-director   ?o wdt:P27 wd:Q17 .
      directors
    the Byzantine       nothing         "the catalogue contains no question templates related to
      succession                         monarchs, rulers, political succession, predecessors or
                                         successors, or historical dynasties"

"Star Wars" then produced the moment this whole design was built for. The model claimed `Q82347`
was the franchise; the label gate looked it up and found **"america's next top model, season 2"**.
Without that check the pipeline would have published a pack called *Star Wars* full of reality-
television credits — plausibly named, entirely wrong, and permanent, because a published fact id
is permanent. It refused instead.

Refusing was right and stopping there was not: the gate is holding the correction in its hand. So a
refusal goes back to the model — and telling it "Q82347 is actually a reality show" was not enough
either. It guessed again and produced `Q809`, the Polish language. Two guesses, two unrelated
entities, because **recalling five-digit identifiers is not something a language model does**, and
asking more politely does not change that.

So it is not asked to. A refusal now carries Wikidata's own search results for the name the model
used — `Q462 Star Wars — American epic space opera media franchise` — and the model picks from real
candidates instead of reciting.

That fixed which `Q`, and the same problem promptly reappeared one level along: given the right
entity, the model guessed the *property*. `?s wdt:P361 wd:Q462 .` is a defensible reading of "part
of" and yields exactly one fact, and the next template's retry invented `Q8234`, a valley in
Saxony. Four wrong identifiers across three attempts is not a prompting problem.

So properties are looked up too. [linking_properties] asks Wikidata which properties actually
connect a template's subjects to that entity, commonest first, and the retry shows them — so the
second attempt is a choice from a list rather than another guess. Reading beats recall, twice, and
this is the division of labour the whole design was reaching for: the model knows that "the music
of Star Wars" means constraining a composer's works to a franchise, Wikidata knows that the
franchise is `Q462` and the link is `P8345`, and neither is asked to do the other's job.

And then the first real *publish* run found the one thing none of the gates can: **`?s wdt:P30
wd:Q15 .` matched nothing.** It is exactly right — "on the continent of Africa", both ids verified
against Wikidata — and almost no river carries P30, so it selected zero rivers. A clause can be
correct about meaning and wrong about coverage, and nothing that reads the clause can tell the
difference. Only the endpoint knows.

So the endpoint is asked, and when the answer is empty the model is told precisely that — which
clause, and how little it matched — and asked to route around it. `?s wdt:P17 ?c . ?c wdt:P30
wd:Q15 .` reaches the same meaning through a property rivers actually carry. What never happens
is widening back to the un-narrowed query; a model with nothing better to offer costs its template,
not the topic's honesty.

Worth reading closely, because two of those are better than the brief asked for. "Chemistry" was
not one template but three, with two of them narrowed to `wd:Q11344` — so the pack asks who
discovered an element and what an element is named after, rather than who discovered anything at
all. And "films by Japanese directors" narrowed **`?o`**, the answer, rather than the subject: the
grammar permits it and nothing in the prompt suggested it. Every Q- and P-number in that column was
checked against its real Wikidata label before a single fact was harvested, which is the whole
reason a model is allowed near this at all.

Two findings came out of that run and both are in the code above. The model named `gemini-2.5-flash`
as retired and `gemini-3.6-flash` as its replacement, which is why a 404 naming a successor is
followed once. And "rivers of Africa" was **refused by this file's own gate**, not by the model: it
declared its entities as `wd:Q15`, the spelling they have inside the clause, and `bare()` did not
exist yet. Refusing a right answer over a spelling is precisely what a strict gate is meant not to
do, and it is the kind of thing no amount of offline testing against a fake model would have found
— the fake answered the way the gate expected, because the same person wrote both.

    python3 tools/topic_llm.py --self-test               # offline, no key
    python3 tools/topic_llm.py --topic "rivers of Africa" --explain
    python3 tools/topic_llm.py --topic "rivers of Africa"    # asks the model, caches, verifies
"""

import argparse
import json
import os
import re
import sys
import time
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

# Read off a real 404 rather than chosen from memory. The first call this file made was to
# `gemini-2.5-flash`, and the API answered: "This model models/gemini-2.5-flash is no longer
# available to new users. Please update your code to use models/gemini-3.6-flash." Overridable
# with GEMINI_MODEL, and followed automatically once if this one goes the same way.
DEFAULT_GEMINI_MODEL = "gemini-3.6-flash"


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

# A prefixed id and a bare one name the same entity. The first real model call declared its
# entities as `wd:Q15` — the spelling they have inside the clause, which is a perfectly reasonable
# reading of "list every Q-number your clause uses" — and the gate refused a correct answer for it.
# Refusing a right answer over a spelling is the failure mode a strict gate is *supposed* to avoid,
# so both spellings are accepted and the prompt now says which one it wants.
PREFIXED = re.compile(r"^(?:wdt|wd|ps|pq|p):")


def bare(entity_id):
    """`wd:Q15` and `Q15` are the same id. Returns it the way the rest of this file writes it."""
    return PREFIXED.sub("", str(entity_id or "").strip())


def normalise(label):
    return " ".join(str(label or "").lower().replace("’", "'").split())


def ask_wikidata(query, ask=None, sleep=time.sleep):
    """One SPARQL query, waiting out a rate limit rather than dying on it.

    Wikidata answers 429 when asked too often, and this file asks three times per proposal now:
    the labels, the entity search, and the properties that link them. A run that crashed with an
    uncaught `HTTPError: 429` is what added this — the traceback replaced the topic's own report,
    so the log said nothing about what the model had decided.
    """
    for attempt in range(1, ATTEMPTS + 1):
        try:
            return (ask or gen.sparql)(query)
        except urllib.error.HTTPError as error:
            if error.code not in TRANSIENT or attempt == ATTEMPTS:
                raise
            wait = BACKOFF * attempt
            print(f"  wikidata answered {error.code}; waiting {wait}s and asking again")
            sleep(wait)
    raise Refused("wikidata did not answer")


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
    try:
        rows = ask_wikidata(query, ask)["results"]["bindings"]
    except Exception as exc:
        # Fail closed. An id this file could not check is an id it must not accept — the whole
        # reason a model is allowed near published content is that every id it names is verified,
        # and "the check was unavailable" is not verification.
        raise Refused(f"could not verify these ids against Wikidata: {exc}") from exc
    found = {}
    for row in rows:
        qid = row["item"]["value"].rsplit("/", 1)[-1]
        found.setdefault(qid, set()).add(normalise(row["label"]["value"]))
    return found


class WrongEntity(Refused):
    """The id names something else. Carries what the model thought it was, so it can be looked up."""

    def __init__(self, message, entity_id, claimed):
        super().__init__(message)
        self.entity_id = entity_id
        self.claimed = claimed


SEARCH = "https://www.wikidata.org/w/api.php"


def search_entities(text, kind="item", limit=7, fetch=None):
    """Real Wikidata entities whose name matches [text], with descriptions to tell them apart.

    The model is good at "the constraint is: part of the series = Star Wars" and bad at "Star Wars
    is Q462". Asked about Star Wars it produced Q82347 — "America's Next Top Model, season 2" —
    and, told that was wrong, produced Q809, the Polish language. Two guesses, two unrelated
    entities, because recalling arbitrary five-digit identifiers is not a thing a language model
    does reliably and no amount of asking again fixes it.

    So it is not asked to. Wikidata's own search returns the real candidates and the model picks
    from them, which is the division of labour that plays to what each side is actually good at.
    """
    params = {"action": "wbsearchentities", "search": text, "language": "en", "uselang": "en",
              "format": "json", "limit": str(limit), "type": kind}
    url = f"{SEARCH}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(url, headers={"User-Agent": gen.UA})
    try:
        if fetch:
            body = fetch(url)
        else:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = json.load(response)
    except Exception:
        # A search that cannot run is not a reason to fail the topic; it just means the retry goes
        # out without candidates, exactly as it did before this existed.
        return []
    return [{"id": hit.get("id", ""), "label": hit.get("label", ""),
             "description": hit.get("description", "")}
            for hit in body.get("search", []) if hit.get("id")]


def linking_properties(where, entity_id, ask=None, limit=6):
    """How the subjects of a template are *actually* connected to an entity, most common first.

    The third time the same lesson arrived. Entity search fixed which `Q` to use; the model then
    guessed the property instead — `?s wdt:P361 wd:Q462 .` for Star Wars music, which is a
    defensible reading of "part of" and yields one fact — and on the next template invented
    `Q8234`, a valley in Saxony. It cannot reliably produce identifiers of either kind, and no
    prompt fixes that.

    So this asks Wikidata which properties really link these subjects to that entity, and the
    model chooses from the answer. One query, bounded by the template's own WHERE, and a failure
    costs the hint rather than the topic.
    """
    query = f"""
    SELECT ?p (COUNT(DISTINCT ?s) AS ?n) WHERE {{
      {where}
      ?s ?p wd:{entity_id} .
    }}
    GROUP BY ?p ORDER BY DESC(?n) LIMIT {limit}
    """
    try:
        rows = ask_wikidata(query, ask)["results"]["bindings"]
    except Exception:
        # Unlike the label check, this one is only a hint. Losing it costs the model a better
        # second guess, not the guarantee that what it named is what it said it was.
        return []
    found = []
    for row in rows:
        # `?p` binds the direct-claim predicate URI; the P-number is its last segment. Read
        # defensively: this is a hint, and a response that is not the shape expected should cost
        # the hint rather than the run.
        try:
            pid = row["p"]["value"].rsplit("/", 1)[-1]
            count = int(row["n"]["value"])
        except (KeyError, TypeError, ValueError):
            continue
        if gen.WELL_FORMED.match(pid) and pid.startswith("P"):
            found.append((pid, count))
    return found


def links_for(where, entity_id, ask=None):
    """The line a retry prompt shows: which properties connect these subjects to that entity."""
    found = linking_properties(where, entity_id, ask=ask)
    if not found:
        return ""
    names = fetch_labels([pid for pid, _ in found], ask=ask)
    rows = "\n".join(
        f"      wdt:{pid}  {sorted(names.get(pid, {'?'}))[0]} — links {n} of them"
        for pid, n in found)
    return (f"\n    Wikidata says the subjects this template selects are connected to "
            f"wd:{entity_id} by these properties. Use one of them rather than choosing a "
            f"property from memory:\n{rows}\n")


def candidates_for(claimed, entity_id, fetch=None):
    """The line a retry prompt shows: what that id really is, and what the model probably meant."""
    kind = "property" if str(entity_id).startswith("P") else "item"
    found = search_entities(claimed, kind=kind, fetch=fetch)
    if not found:
        return ""
    rows = "\n".join(f"      {hit['id']}  {hit['label']}"
                      + (f" — {hit['description']}" if hit["description"] else "")
                      for hit in found)
    return (f"\n    Wikidata's own search for {json.dumps(claimed)} returns these real "
            f"entities. Pick from them rather than recalling an id:\n{rows}\n")


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
        eid = bare(str(entry.get("id", "")))
        if not gen.WELL_FORMED.match(eid):
            raise Refused(f"'{entry.get('id')}' is not a well-formed Wikidata id")
        claimed[eid] = str(entry.get("label", "")).strip()

    real = fetch_labels(sorted(claimed), ask=ask)
    for eid, label in sorted(claimed.items()):
        if eid not in real:
            raise Refused(f"{eid} does not exist on Wikidata (the model called it '{label}')")
        if normalise(label) not in real[eid]:
            example = sorted(real[eid])[:3]
            raise WrongEntity(
                f"{eid} is not '{label}' — Wikidata calls it {', '.join(example)}", eid, label)
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

In `entities`, list every Q-number and P-number your clause uses — bare, without the `wd:` or
`wdt:` prefix — with the English label you believe it has. Every one is checked against Wikidata before anything runs. Say what you
actually believe — a guessed id whose label does not match is thrown out, and so is the clause.

One more thing, and it is the failure that actually happens: **a clause can be perfectly correct
and still match nothing**, because Wikidata's coverage is uneven. `?s wdt:P30 wd:Q15 .` reads as
"on the continent of Africa" and is exactly right, and almost no river carries P30 — so narrowing
rivers that way returns zero. Prefer a property the subjects of that template actually carry. When
the obvious one is likely sparse, hop through a related entity that does carry it: a river has a
country (P17), and a country has a continent (P30).

Return JSON only.
"""


def retry_prompt(topic, template_key, clause, problem, templates=None):
    """The second ask, after a proposal failed for a reason the model can act on.

    Two failures reach here and both are recoverable, which is why they share a prompt. A clause
    can name the wrong entity — the gate knows the *real* label and hands it back — or it can name
    the right one and still match nothing, because Wikidata's coverage is uneven. Either way the
    model is told exactly what went wrong, because "try again" without the result is rolling the
    same dice.
    """
    return (
        f"{INSTRUCTIONS}\n"
        f"Topic: {json.dumps(topic)}\n\n"
        f"You already answered this topic, and one of your narrowings did not work:\n\n"
        f"    template: {template_key}\n"
        f"    narrow:   {clause or '(none)'}\n"
        f"    problem:  {problem}\n\n"
        f"Propose a *different* narrowing for this one template, and return only that template. "
        f"If the problem was an entity id, use the one that really means what you intended — the "
        f"message above tells you what the id you chose actually is. If the problem was that the "
        f"clause matched nothing, reach the same meaning through a property those subjects "
        f"actually carry, usually by hopping through a related entity. If the topic's meaning "
        f"cannot be reached any other way, return no templates and say so in `note`; that is a "
        f"better answer than a clause you do not believe in.\n\n"
        f"Catalogue:\n{json.dumps(catalogue(templates), ensure_ascii=False, indent=1)}\n"
    )


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


class Retired(Refused):
    """The model is gone and the API named its replacement. Carries the name it gave."""

    def __init__(self, message, replacement):
        super().__init__(message)
        self.replacement = replacement


# Google answers a retired model with a 404 whose message names the successor in prose. Read
# rather than guessed at: the exact wording is recorded below, from the probe run that found it.
RETIRED = re.compile(r"use\s+models/([A-Za-z0-9.\-]+)")


# Overload and rate limits are weather, not answers. A free tier returns them routinely and the
# first Star Wars run lost a whole template to one 503 — reported as though the model had refused,
# which it had not. Bounded, because a provider that is down stays down and a topic run should say
# so rather than sit in a loop.
TRANSIENT = {429, 500, 502, 503, 504}
ATTEMPTS = 3
BACKOFF = 4

# A 429 has two meanings and they need opposite responses. "This model is currently experiencing
# high demand" clears in seconds and is worth waiting for. "You exceeded your current quota" is a
# daily allowance that will not return before tomorrow, and backing off three times to discover
# that wastes twelve seconds and says nothing useful — the free tier's cap is what it is, and the
# run should name it and stop.
EXHAUSTED = re.compile(r"(?i)exceeded your current quota|quota exceeded for metric")


def post(url, payload, headers, timeout=90, sleep=time.sleep):
    request = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers})
    for attempt in range(1, ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")[:400]
            if error.code == 404:
                named = RETIRED.search(detail)
                if named:
                    raise Retired(f"{DEFAULT_GEMINI_MODEL} is retired", named.group(1)) from error
            if error.code == 429 and EXHAUSTED.search(detail):
                limit = re.search(r"limit:\s*(\d+)", detail)
                raise Refused(
                    "the API key's quota is spent"
                    + (f" (limit: {limit.group(1)} requests)" if limit else "")
                    + " — this is a daily allowance, not a hiccup, so waiting minutes will not "
                      "help. Raise the quota, enable billing, or set ANTHROPIC_API_KEY and pass "
                      "--provider anthropic.") from error
            if error.code in TRANSIENT and attempt < ATTEMPTS:
                wait = BACKOFF * attempt
                print(f"  the model API answered {error.code}; waiting {wait}s and asking again")
                sleep(wait)
                continue
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


def ask_gemini(topic, templates=None, model=None, key=None, prompt=None):
    """One call, with one retry if the model named has been retired since this was written.

    The retry is not defensiveness for its own sake. The first real call this file ever made came
    back 404: `gemini-2.5-flash` was retired for new users, and the message said which model to use
    instead. A model name has a shelf life measured in months and this pipeline runs monthly, so
    without this a topic run would one day fail with a 404 that reads like a broken key — and the
    fix would be a code change nobody knew was needed. Following the API's own instruction once,
    loudly, turns an outage into a line in a log. It is recorded in the cache, so which model
    answered is never a guess.
    """
    model = model or os.environ.get("GEMINI_MODEL", DEFAULT_GEMINI_MODEL)
    key = key or os.environ.get("GEMINI_API_KEY", "")
    if not key:
        raise Refused("GEMINI_API_KEY is not set — this step only runs where the secret is")

    def call(name):
        return post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{name}:generateContent",
            {"contents": [{"parts": [{"text": prompt or prompt_for(topic, templates)}]}],
             "generationConfig": {"temperature": 0, "responseMimeType": "application/json",
                                  "responseSchema": SCHEMA}},
            {"x-goog-api-key": key})

    try:
        body = call(model)
    except Retired as exc:
        print(f"  {model} is retired; the API says to use {exc.replacement}. Following it once —"
              f" set GEMINI_MODEL to make that the default.")
        model = exc.replacement
        body = call(model)

    try:
        text = body["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError) as exc:
        raise Refused(f"the model returned no answer: {json.dumps(body)[:300]}") from exc
    return loads(text), model


def ask_anthropic(topic, templates=None, model=None, key=None, prompt=None):
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
         "messages": [{"role": "user",
                       "content": prompt or prompt_for(topic, templates)}]},
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
             cache_path=CACHE, fetch=None):
    """The verified plan for a topic: cached if it has been asked, asked and verified if not.

    Returns (plan, note, cached). [plan] is a list of verified proposals; a topic the model could
    not serve returns an empty list and a note saying why, which is an answer rather than a
    failure — the same honesty `topic_pack.py` prints when a topic routes nowhere.
    """
    cache = load_cache(cache_path) if use_cache else {}
    slot = cache.get(cache_key(topic))
    if slot:
        return slot["templates"], slot.get("note", ""), True

    raw, used_model = PROVIDERS[provider](topic, templates, model=model)
    verified, refusals = [], []
    for proposal in raw.get("templates") or []:
        key = str(proposal.get("key", "?"))
        try:
            verified.append(verify(proposal, templates, ask=ask))
            continue
        except WrongEntity as error:
            # Bound to names that outlive the block: Python deletes an `as` target when the except
            # clause ends, and the retry below needs both the reason and the real candidates.
            reason = str(error) + candidates_for(error.claimed, error.entity_id, fetch=fetch)
        except Refused as error:
            reason = str(error)
        print(f"  refused — {key}: {reason.splitlines()[0]}")

        # A refusal is not the end of the topic. The commonest one by far is a hallucinated
        # entity id, and the gate that caught it knows what that id *really* is — so the model is
        # told, and asked again. "Star Wars" was the case that argued for this: the model claimed
        # Q82347 was the franchise, the label check found "america's next top model, season 2",
        # and three templates were dropped over one wrong number. Handing back the true label is
        # the difference between a topic that routes nowhere and one that works.
        try:
            better = retry(topic, key, str(proposal.get("narrow", "")).strip(), reason,
                           provider=provider, templates=templates, ask=ask, model=model,
                           cache_path=cache_path, use_cache=False)
        except Refused as second:
            better, reason = None, str(second)
        if better:
            print(f"  {key}: corrected after being told what it had really named")
            verified.append(better)
        else:
            refusals.append(f"{key}: {reason}")

    note = str(raw.get("note", "")).strip()
    if refusals:
        note = "; ".join([note] + refusals) if note else "; ".join(refusals)

    cache[cache_key(topic)] = {"topic": topic, "provider": provider, "model": used_model,
                               "templates": verified, "note": note}
    if use_cache:
        save_cache(cache, cache_path)
    return verified, note, False


def retry(topic, template_key, clause, problem, provider="gemini", templates=None, ask=None,
          model=None, cache_path=CACHE, use_cache=True):
    """A second proposal for one template, after the first failed for a reason worth reporting.

    Returns a verified proposal, or None if the model had nothing better — which is a real answer,
    and the caller drops the template rather than falling back to something broader.

    Every gate that ran on the first proposal runs on this one. A retry is not a second chance to
    get past them; it is a second chance to be right.
    """
    asker = PROVIDERS[provider]
    raw, used_model = asker(topic, templates, model=model,
                            prompt=retry_prompt(topic, template_key, clause, problem, templates))
    for proposal in raw.get("templates") or []:
        if str(proposal.get("key", "")).strip() != template_key:
            continue
        if clause and not str(proposal.get("narrow", "")).strip():
            # Declining to narrow after a *narrowing* failed means "I cannot reach this meaning",
            # not "publish it broad" — the un-narrowed query is the bug this path exists to
            # prevent. When the first attempt was refused outright there was never a working
            # narrowing to lose, so an honest un-narrowed answer is allowed to stand.
            continue
        try:
            better = verify(proposal, templates, ask=ask)
        except Refused as exc:
            print(f"  retry refused — {template_key}: {exc}")
            continue
        if use_cache:
            remember(topic, better, used_model, cache_path)
        return better
    print(f"  {template_key}: the model had no better narrowing to offer")
    return None


def remember(topic, proposal, model, cache_path=CACHE):
    """Replace a template's entry in the cache with one that actually worked.

    So a clause that failed is not tried again on the next run — and, more importantly, so the
    committed cache records what the pack was really built from rather than the first guess.
    """
    cache = load_cache(cache_path)
    slot = cache.setdefault(cache_key(topic),
                            {"topic": topic, "model": model, "templates": [], "note": ""})
    slot["templates"] = [p for p in slot["templates"] if p.get("key") != proposal["key"]]
    slot["templates"].append(proposal)
    slot["model"] = model
    save_cache(cache, cache_path)


# --- self-test ------------------------------------------------------------------------------

def self_test():
    import io
    failures = []
    _seen = []

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
    check("an id declared with its prefix is the same id, not a malformed one",
          check_entities([{"id": "wd:Q15", "label": "Africa"}], ask=fake_ask) == {"Q15": "Africa"})
    check("and a prefixed property id likewise",
          check_entities([{"id": "wdt:P30", "label": "continent"}], ask=fake_ask) == {"P30": "continent"})
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

    retired = ('{"error": {"code": 404, "message": "This model models/gemini-2.5-flash is no '
               'longer available to new users. Please update your code to use '
               'models/gemini-3.6-flash for the latest features and improvements."}}')
    check("a retired model's replacement is read out of the API's own message",
          RETIRED.search(retired).group(1) == "gemini-3.6-flash")
    check("and a 404 that names no replacement is just a refusal",
          RETIRED.search('{"error": {"code": 404, "message": "not found"}}') is None)

    def fake_links(query):
        """Wikidata answering "how are these connected?" — the question the model was guessing at."""
        if "?p wd:Q462" not in query:
            return {"results": {"bindings": []}}
        return {"results": {"bindings": [
            {"p": {"value": "http://www.wikidata.org/prop/direct/P8345"}, "n": {"value": "47"}},
            {"p": {"value": "http://www.wikidata.org/prop/direct/P361"}, "n": {"value": "1"}},
        ]}}

    # Wikidata rate-limits, and a run once died on an uncaught 429 with a traceback where the
    # topic's report should have been.
    limited = {"n": 0}

    def rate_limited(query):
        limited["n"] += 1
        if limited["n"] < 3:
            raise urllib.error.HTTPError("u", 429, "slow down", {}, io.BytesIO(b""))
        return {"results": {"bindings": []}}

    paused = []
    check("a rate-limited Wikidata is waited out, not crashed on",
          ask_wikidata("SELECT 1", rate_limited, sleep=paused.append) == {"results": {"bindings": []}})
    check("with a backoff between tries", paused == [4, 8])

    def always_limited(query):
        raise urllib.error.HTTPError("u", 429, "slow down", {}, io.BytesIO(b""))

    check("an id that cannot be checked is refused, never assumed good",
          "could not verify" in (refused(check_entities, [{"id": "Q15", "label": "Africa"}],
                                         ask=always_limited) or ""))
    check("but a hint that cannot be fetched costs only the hint",
          linking_properties("?s wdt:P86 ?o .", "Q462", ask=always_limited) == [])

    check("the properties that really link subjects to an entity are read off Wikidata",
          linking_properties("?s wdt:P86 ?o .", "Q462", ask=fake_links)
          == [("P8345", 47), ("P361", 1)])
    check("and they come back commonest first, which is the one worth using",
          linking_properties("?s wdt:P86 ?o .", "Q462", ask=fake_links)[0][0] == "P8345")
    check("a query that cannot run costs the hint, not the topic",
          linking_properties("?s wdt:P86 ?o .", "Q1", ask=fake_links) == [])

    def links_and_labels(query):
        if "VALUES" in query:
            return {"results": {"bindings": [
                {"item": {"value": "http://www.wikidata.org/entity/P8345"},
                 "label": {"value": "media franchise"}},
                {"item": {"value": "http://www.wikidata.org/entity/P361"},
                 "label": {"value": "part of"}}]}}
        return fake_links(query)

    shown_links = links_for("?s wdt:P86 ?o .", "Q462", ask=links_and_labels)
    check("the hint names each property and how many subjects it links",
          "wdt:P8345" in shown_links and "media franchise" in shown_links
          and "links 47 of them" in shown_links)
    check("and tells the model to choose rather than recall",
          "rather than choosing a property from memory" in shown_links)

    check("a search result is read down to id, label and description",
          search_entities("Star Wars", fetch=lambda url: {"search": [
              {"id": "Q462", "label": "Star Wars", "description": "franchise"}]})
          == [{"id": "Q462", "label": "Star Wars", "description": "franchise"}])
    check("a search that cannot run costs candidates, not the topic",
          search_entities("x", fetch=lambda url: (_ for _ in ()).throw(OSError("no route"))) == [])
    def capturing(url):
        _seen.append(url)
        return {"search": [{"id": "P17", "label": "country", "description": "sovereign state"}]}

    shown = candidates_for("country", "P17", fetch=capturing)
    check("a P-number is looked up among properties, not items",
          _seen and "type=property" in _seen[0])
    check("an item id is looked up among items",
          "type=item" in (candidates_for("Africa", "Q15", fetch=capturing) and _seen[1]))
    check("and the candidates are shown with their descriptions, which is what tells them apart",
          "P17" in shown and "sovereign state" in shown)

    waits = []
    calls = {"n": 0}

    def flaky(url, data=None, timeout=None):
        calls["n"] += 1
        if calls["n"] < 3:
            raise urllib.error.HTTPError(url, 503, "busy", {}, io.BytesIO(b"high demand"))
        return io.BytesIO(json.dumps({"ok": True}).encode())

    real_open = urllib.request.urlopen
    urllib.request.urlopen = lambda req, timeout=None: flaky(req.full_url, timeout=timeout)
    try:
        answered = post("https://example.invalid", {}, {}, sleep=waits.append)
    finally:
        urllib.request.urlopen = real_open
    check("a 503 is weather, not an answer — it waits and asks again", answered == {"ok": True})
    check("and it backs off rather than hammering", waits == [4, 8])

    # The other 429, which reads the same to a status code and means the opposite.
    def spent(url, data=None, timeout=None):
        raise urllib.error.HTTPError(url, 429, "quota", {}, io.BytesIO(
            b'{"error":{"message":"You exceeded your current quota. Quota exceeded for metric: '
            b'generate_content_free_tier_requests, limit: 20"}}'))

    slept = []
    urllib.request.urlopen = lambda req, timeout=None: spent(req.full_url, timeout=timeout)
    try:
        exhausted = refused(post, "https://example.invalid", {}, {}, sleep=slept.append)
    finally:
        urllib.request.urlopen = real_open
    check("a spent quota is named as such rather than reported as overload",
          "quota is spent" in (exhausted or ""))
    check("and it does not wait for a daily allowance to come back in twelve seconds", slept == [])
    check("the message says how to carry on", "ANTHROPIC_API_KEY" in (exhausted or ""))

    check("JSON in a code fence is still JSON", loads('```json\n{"a": 1}\n```') == {"a": 1})
    check("bare JSON is still JSON", loads('{"a": 1}') == {"a": 1})
    check("prose instead of JSON is refused", refused(loads, "I think the topic is about rivers"))

    # --- the cache -----------------------------------------------------------------------
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "cache.json"
        calls = []

        def fake_model(topic, templates=None, model=None, key=None, prompt=None):
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

        # --- the Star Wars case, which is why a refusal is not the end of a topic ------------
        #
        # The model claimed Q82347 was Star Wars. It is "America's Next Top Model, season 2", and
        # the label gate said so — precisely, with the real label attached. Dropping the template
        # there would route a perfectly answerable topic nowhere over one wrong number, when the
        # gate is holding the correction in its hand.
        WORLD["Q462"] = {"star wars"}
        WORLD["P179"] = {"part of the series"}
        WORLD["Q82347"] = {"america's next top model, season 2"}
        wrong = {"key": "film-director", "narrow": "?s wdt:P179 wd:Q82347 .",
                 "why": "films in the Star Wars series",
                 "entities": [{"id": "P179", "label": "part of the series"},
                              {"id": "Q82347", "label": "Star Wars"}]}
        right = {**wrong, "narrow": "?s wdt:P179 wd:Q462 .",
                 "entities": [{"id": "P179", "label": "part of the series"},
                              {"id": "Q462", "label": "Star Wars"}]}
        told = []

        def fake_search(url):
            """Wikidata's own search, which knows what "Star Wars" is and the model does not."""
            return {"search": [
                {"id": "Q462", "label": "Star Wars",
                 "description": "American epic space opera media franchise"},
                {"id": "Q17738", "label": "Star Wars", "description": "1977 film by George Lucas"},
            ]}

        def hallucinating_model(topic, templates=None, model=None, key=None, prompt=None):
            told.append(prompt)
            return ({"templates": [right if prompt else wrong], "note": ""}, "fake-1")

        PROVIDERS["hallucinating"] = hallucinating_model
        plan, note, _ = plan_for("star wars", "hallucinating", ask=fake_ask,
                                 cache_path=Path(tmp) / "sw.json", fetch=fake_search)
        check("the retry carries real candidates, not just the news that it was wrong",
              "Q462" in (told[1] or "") and "space opera" in (told[1] or ""))
        check("and it says which id is which, so picking is reading rather than recalling",
              "Q17738" in (told[1] or ""))
        check("a hallucinated entity id is corrected rather than dropped",
              [p["narrow"] for p in plan] == ["?s wdt:P179 wd:Q462 ."])
        check("and the model is told what the id it chose actually was",
              "america's next top model" in (told[1] or ""))
        check("the corrected proposal passed every gate the first one did",
              plan and plan[0]["entities"][1]["id"] == "Q462")
        PROVIDERS.pop("hallucinating")

        def stubborn_model(topic, templates=None, model=None, key=None, prompt=None):
            return ({"templates": [wrong], "note": ""}, "fake-1")

        PROVIDERS["stubborn"] = stubborn_model
        plan, note, _ = plan_for("star wars again", "stubborn", ask=fake_ask,
                                 cache_path=Path(tmp) / "sw2.json", fetch=fake_search)
        check("a model that repeats the same wrong id is refused, not indulged", plan == [])
        check("and the reason still reaches the note", "Q82347 is not" in note)
        PROVIDERS.pop("stubborn")

        def lying_model(topic, templates=None, model=None, key=None, prompt=None):
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
