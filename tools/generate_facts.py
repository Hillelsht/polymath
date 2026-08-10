#!/usr/bin/env python3
"""Generate an effectively unlimited Library from Wikidata.

The app shipped 517 facts because 517 seeds were hand-written. Delivery was never the limit —
the app has downloaded packs from the repo since v2 — authorship was. This removes that limit.

Why Wikidata rather than prose:

  * A fact here is a *property*, not a sentence. "Capital of France" is P36 on Q142, so the
    generated fact is true by construction rather than by parsing. Earlier in this project 187
    hand-written YouTube ids turned out to contain 30 real ones; that failure mode is not
    available to a structured source.
  * A property IS an answerType. Every item with P36 yields a capital, so the quiz's distractors
    come out same-typed and plausible for nothing.
  * Sitelinks — how many language Wikipedias carry an article — measure importance, so facts can
    be delivered most-important-first.

Two constraints were found by probing rather than guessed, and both shape the code below.

**Broad queries time out.** The public endpoint gives roughly 60 seconds, and asking it to rank
865,300 paintings by sitelinks exceeds that. The importance floor is the fix — it shrinks the
candidate set, and it selects for importance, which is wanted anyway. When a query still times
out the floor is raised and it is retried. (The ranking also runs in a subquery so the OPTIONAL
image and article lookups touch only the surviving rows. That is worth having but does not fix
a timeout: the inner query still visits and sorts everything that matches.)

**This sandbox cannot reach Wikidata at all**, so no Q-number or P-number in the template table
below can be verified where it was written. Rather than trust them, `preflight()` checks every
id's shape locally and then asks Wikidata for its label, printing what each one actually means.
A wrong id becomes a named line in the CI log instead of a template that silently yields zero.
"""

import argparse
import hashlib
import json
import re
import signal
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "packs" / "library"
SPARQL = "https://query.wikidata.org/sparql"
WIKI_API = "https://en.wikipedia.org/w/api.php"
UA = "SmartTriviaPipeline/1.0 (+https://github.com/hillelsht/smart) python-urllib"

PER_SHARD = 150
MAX_PER_TEMPLATE = 400
# Learn shows this passage. 700 characters is three or four sentences — enough to be worth
# reading, and the single biggest lever on repository size at 8,000 facts.
DETAIL_CHARS = 700
# Each retry raises the importance floor, which is both the timeout fix and a quality filter.
FLOOR_LADDER = [0, 15, 30, 60, 120, 250]
# A template that has timed out twice is not going to squeak through on the third nudge, and
# every attempt costs a full minute of the endpoint's budget. The third try therefore jumps
# straight to the top of the ladder — one deliberately tiny question instead of three
# middling ones that all time out.
MAX_ATTEMPTS = 3
# Without this the run time is unbounded in the number of templates: 22 templates that each
# time out three times is over an hour on its own. When the budget runs out the remaining
# templates are skipped and said to be skipped, and the publish guards below decide whether
# what was collected is worth publishing.
BUDGET_MINUTES = 40
# Publishing far less than last time means something broke upstream, not that the world shrank.
MIN_TOTAL = 400
MIN_FRACTION_OF_PREVIOUS = 0.6

CATEGORIES = {
    "geography": "Geography",
    "history": "History",
    "science": "Science",
    "arts": "Arts & Literature",
    "sports": "Sports & Games",
    "culture": "Pop Culture",
}


class Template:
    """One property, one question shape. Written once, yields thousands of facts."""

    def __init__(self, key, category, answer_type, title, question, statement, where, floor=0):
        self.key = key
        self.category = category
        self.answer_type = answer_type
        self.title = title
        self.question = question
        self.statement = statement
        self.where = where
        self.floor = floor


TEMPLATES = [
    # --- Geography ----------------------------------------------------------------------
    Template("capital", "geography", "capital",
             "Capital of {s}", "What is the capital of {s}?", "{o} is the capital of {s}.",
             "?s wdt:P31 wd:Q6256 ; wdt:P36 ?o ."),
    Template("currency", "geography", "currency",
             "Currency of {s}", "What is the currency of {s}?", "The currency of {s} is the {o}.",
             "?s wdt:P31 wd:Q6256 ; wdt:P38 ?o ."),
    Template("continent", "geography", "continent",
             "Continent of {s}", "Which continent is {s} in?", "{s} is in {o}.",
             "?s wdt:P31 wd:Q6256 ; wdt:P30 ?o ."),
    # No class constraint: only watercourses carry P403, so the property selects the subject.
    Template("river-mouth", "geography", "body of water",
             "Mouth of the {s}", "Which body of water does the {s} flow into?",
             "The {s} flows into {o}.",
             "?s wdt:P403 ?o .", floor=30),
    Template("mountain-range", "geography", "mountain range",
             "Range of {s}", "Which range is {s} part of?", "{s} is part of {o}.",
             "?s wdt:P4552 ?o .", floor=30),

    # --- Science ------------------------------------------------------------------------
    Template("element-symbol", "science", "chemical symbol",
             "Symbol for {s}", "What is the chemical symbol for {s}?",
             "The chemical symbol for {s} is {o}.",
             "?s wdt:P246 ?o ."),
    Template("moon-parent", "science", "astronomical body",
             "Orbit of {s}", "Which body does {s} orbit?", "{s} orbits {o}.",
             "?s wdt:P31 wd:Q2537 ; wdt:P397 ?o .", floor=15),
    Template("discoverer", "science", "discoverer",
             "Discovery of {s}", "Who discovered {s}?", "{s} was discovered by {o}.",
             "?s wdt:P61 ?o .", floor=30),
    Template("genus", "science", "genus",
             "Genus of the {s}", "Which genus does the {s} belong to?",
             "The {s} belongs to the genus {o}.",
             "?s wdt:P105 wd:Q7432 ; wdt:P171 ?o .", floor=60),
    Template("named-after", "science", "namesake",
             "Namesake of {s}", "What is {s} named after?", "{s} is named after {o}.",
             "?s wdt:P138 ?o .", floor=60),

    # --- Arts & Literature ---------------------------------------------------------------
    # Paintings are the template that timed out at 865,300 items; the floor is what makes it fit.
    Template("painting-creator", "arts", "painter",
             "Painter of {s}", "Who painted {s}?", "{s} was painted by {o}.",
             "?s wdt:P31 wd:Q3305213 ; wdt:P170 ?o .", floor=30),
    Template("book-author", "arts", "author",
             "Author of {s}", "Who wrote {s}?", "{s} was written by {o}.",
             "?s wdt:P31 wd:Q7725634 ; wdt:P50 ?o .", floor=15),
    Template("sculpture-creator", "arts", "sculptor",
             "Sculptor of {s}", "Who sculpted {s}?", "{s} was sculpted by {o}.",
             "?s wdt:P31 wd:Q860861 ; wdt:P170 ?o .", floor=15),
    Template("composer", "arts", "composer",
             "Composer of {s}", "Who composed {s}?", "{s} was composed by {o}.",
             "?s wdt:P86 ?o .", floor=15),

    # --- History -------------------------------------------------------------------------
    Template("battle-conflict", "history", "war",
             "The {s}", "Which war was the {s} part of?", "The {s} was part of {o}.",
             "?s wdt:P31 wd:Q178561 ; wdt:P361 ?o .", floor=15),
    Template("birthplace", "history", "birthplace",
             "Birthplace of {s}", "Where was {s} born?", "{s} was born in {o}.",
             "?s wdt:P31 wd:Q5 ; wdt:P19 ?o .", floor=80),
    Template("citizenship", "history", "country",
             "Nationality of {s}", "Which country was {s} a citizen of?",
             "{s} was a citizen of {o}.",
             "?s wdt:P31 wd:Q5 ; wdt:P27 ?o .", floor=100),

    # --- Culture -------------------------------------------------------------------------
    Template("film-director", "culture", "director",
             "Director of {s}", "Who directed {s}?", "{s} was directed by {o}.",
             "?s wdt:P31 wd:Q11424 ; wdt:P57 ?o .", floor=30),
    Template("dish-origin", "culture", "country",
             "Origin of {s}", "Which country does {s} come from?", "{s} comes from {o}.",
             "?s wdt:P31 wd:Q746549 ; wdt:P495 ?o .", floor=15),
    Template("writing-system", "culture", "writing system",
             "Script of {s}", "Which writing system is {s} written in?",
             "{s} is written in the {o} script.",
             "?s wdt:P282 ?o .", floor=40),

    # --- Sports --------------------------------------------------------------------------
    Template("club-country", "sports", "country",
             "Home of {s}", "Which country does {s} play in?", "{s} plays in {o}.",
             "?s wdt:P31 wd:Q476028 ; wdt:P17 ?o .", floor=15),
    Template("athlete-sport", "sports", "sport",
             "Sport of {s}", "Which sport is {s} known for?", "{s} is known for {o}.",
             "?s wdt:P106 wd:Q2066131 ; wdt:P641 ?o .", floor=30),
]


# --- Wikidata ----------------------------------------------------------------------------

class BadQuery(Exception):
    """The endpoint rejected the query itself. Retrying at a higher floor cannot help."""


class Overtime(Exception):
    """A request ran past its wall-clock allowance."""


@contextmanager
def hard_timeout(seconds):
    """Bound a whole request by the wall clock.

    `urlopen(timeout=...)` is a *socket* timeout: it bounds each individual read, not the
    request. A query that dribbles its results back never trips it, which is how a run given an
    eight-minute budget was still going after twenty-six minutes — the budget is checked
    between templates, and control never came back to check it. SIGALRM bounds the thing that
    was actually meant to be bounded.
    """
    def fire(signum, frame):
        raise Overtime(f"exceeded {seconds:.0f}s")

    previous = signal.signal(signal.SIGALRM, fire)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def sparql(query, timeout=65):
    url = f"{SPARQL}?{urllib.parse.urlencode({'query': query, 'format': 'json'})}"
    request = urllib.request.Request(
        url, headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
    try:
        with hard_timeout(timeout + 5):
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.load(response)
    except urllib.error.HTTPError as error:
        # 400 is a malformed query — a typo in a template, not a set too large to rank.
        # Reporting it as a timeout would send the retry loop up the floor ladder pointlessly
        # and then blame the endpoint for something this file got wrong.
        if error.code == 400:
            detail = error.read().decode("utf-8", "replace").strip().splitlines()
            raise BadQuery(detail[0][:300] if detail else "malformed query") from error
        raise


ENTITY_TOKEN = re.compile(r"\bwdt?:([A-Za-z0-9]+)")
WELL_FORMED = re.compile(r"^[QP][1-9][0-9]*$")


def referenced_ids(template):
    """Every Wikidata entity a template names, in the order it names them."""
    seen = []
    for token in ENTITY_TOKEN.findall(template.where):
        if token not in seen:
            seen.append(token)
    return seen


def malformed_ids(template):
    """Ids that cannot exist. Catches a typo with no network at all."""
    return [i for i in referenced_ids(template) if not WELL_FORMED.match(i)]


def preflight(templates, offline=False):
    """Resolve every id to its label so a wrong one is named rather than yielding zero facts.

    Nothing in this file's template table could be checked where it was written — the sandbox
    has no route to Wikidata. This is how the ids get checked anyway: the shape test runs
    anywhere, and the label lookup runs wherever the generator itself runs.
    """
    usable, rejected = [], []
    for template in templates:
        bad = malformed_ids(template)
        if bad:
            rejected.append((template, f"not a Wikidata id: {', '.join(bad)}"))
        else:
            usable.append(template)

    if offline or not usable:
        for template, why in rejected:
            print(f"  DROP {template.key:<20} {why}")
        return usable

    ids = []
    for template in usable:
        for entity in referenced_ids(template):
            if entity not in ids:
                ids.append(entity)

    values = " ".join(f"wd:{entity}" for entity in ids)
    try:
        data = sparql(f'SELECT ?e ?eLabel WHERE {{ VALUES ?e {{ {values} }} '
                      f'SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }} }}')
    except Exception as exc:
        # Not fatal: the harvest below will fail loudly enough on its own.
        print(f"  (label lookup unavailable: {type(exc).__name__} — continuing unverified)")
        return usable

    labels = {}
    for row in data["results"]["bindings"]:
        entity = row["e"]["value"].rsplit("/", 1)[-1]
        label = row.get("eLabel", {}).get("value", "")
        # An id with no item behind it comes back labelled with its own id.
        labels[entity] = "" if label == entity else label

    for entity in ids:
        label = labels.get(entity, "")
        print(f"  {entity:<12} {label or '*** does not resolve ***'}")

    checked = []
    for template in usable:
        missing = [i for i in referenced_ids(template) if not labels.get(i)]
        if missing:
            rejected.append((template, f"unresolved: {', '.join(missing)}"))
        else:
            checked.append(template)

    for template, why in rejected:
        print(f"  DROP {template.key:<20} {why}")
    return checked


def build_query(template, floor, limit):
    """Rank first in a subquery, decorate second.

    This keeps the OPTIONAL image and article lookups off the whole matched set — they touch
    `limit` rows instead of 865,300. Worth having, but it is **not** what fixes a timeout: the
    inner query still has to visit every matching item and sort it, and `LIMIT` cannot help a
    sort that has to see everything first. The importance floor is what actually shrinks the
    set, and for the largest properties it is the only thing that does.
    """
    return f"""
    SELECT ?s ?sLabel ?o ?oLabel ?sl ?img ?article WHERE {{
      {{
        SELECT ?s ?o ?sl WHERE {{
          {template.where}
          ?s wikibase:sitelinks ?sl .
          FILTER(?sl >= {floor})
        }}
        ORDER BY DESC(?sl)
        LIMIT {limit}
      }}
      OPTIONAL {{ ?s wdt:P18 ?img }}
      OPTIONAL {{ ?article schema:about ?s ; schema:isPartOf <https://en.wikipedia.org/> . }}
      SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }}
    }}
    """


def attempt_floors(template):
    """The floors to try, cheapest question last-resorted to the narrowest one."""
    rungs = [f for f in FLOOR_LADDER if f >= template.floor] or [template.floor]
    if len(rungs) <= MAX_ATTEMPTS:
        return rungs
    # Keep the template's own floor, the top of the ladder, and one step in between.
    return [rungs[0], rungs[len(rungs) // 2], rungs[-1]]


def harvest(template, limit):
    """Query, raising the importance floor until the endpoint can answer inside its budget."""
    for attempt, floor in enumerate(attempt_floors(template)):
        try:
            rows = sparql(build_query(template, floor, limit))["results"]["bindings"]
            note = f"floor {floor}" + (f", raised {attempt}x" if attempt else "")
            return rows, note
        except BadQuery as exc:
            # A rejected query is this file's bug and every floor will reproduce it.
            return [], f"malformed query: {exc}"
        except Exception as exc:
            print(f"    floor {floor}: {type(exc).__name__} — raising the importance floor")
            time.sleep(2)
    return [], "gave up: every floor timed out"


# --- Facts -------------------------------------------------------------------------------

def commons_image(url):
    """P18 gives a Commons file URL; Special:FilePath serves a sized thumbnail off it."""
    if not url:
        return None
    name = urllib.parse.unquote(url.rsplit("/", 1)[-1])
    return ("https://commons.wikimedia.org/wiki/Special:FilePath/"
            f"{urllib.parse.quote(name)}?width=800")


def wiki_title(article_url):
    if not article_url:
        return None
    return urllib.parse.unquote(article_url.rsplit("/", 1)[-1]).replace("_", " ")


def make_facts(template, rows):
    """Turn result rows into facts, dropping everything that would make a bad question.

    The ambiguity rule is the important one. A country with two official currencies produces
    two rows, and both would become facts with the same id — which `validate` would catch as a
    duplicate and refuse the entire corpus over. More to the point, "What is the currency of
    Panama?" with two correct answers is a broken quiz question. Subjects with more than one
    answer are dropped rather than arbitrarily resolved.
    """
    grouped = {}
    for row in rows:
        subject = row.get("sLabel", {}).get("value", "").strip()
        answer = row.get("oLabel", {}).get("value", "").strip()
        qid = row["s"]["value"].rsplit("/", 1)[-1]

        # An unresolved label comes back as the raw Q-number; a fact reading "Who painted
        # Q12345?" is worse than no fact.
        if not subject or not answer:
            continue
        if re.fullmatch(r"Q\d+", subject) or re.fullmatch(r"Q\d+", answer):
            continue
        if answer.lower() == subject.lower():
            continue

        entry = grouped.setdefault(qid, {"answers": set(), "row": row, "subject": subject})
        entry["answers"].add(answer)
        # Prefer a row that carries both decorations; OPTIONALs vary row to row.
        if row.get("img") and not entry["row"].get("img"):
            entry["row"] = row
        if row.get("article") and not entry["row"].get("article"):
            entry["row"] = row

    facts = []
    for qid, entry in grouped.items():
        if len(entry["answers"]) != 1:
            continue
        row, subject = entry["row"], entry["subject"]
        answer = next(iter(entry["answers"]))
        title = wiki_title(row.get("article", {}).get("value"))
        if not title:
            continue
        links = int(float(row.get("sl", {}).get("value", 0)))

        facts.append({
            "id": f"wd-{template.key}-{qid}",
            "title": template.title.format(s=subject),
            "statement": template.statement.format(s=subject, o=answer),
            "question": template.question.format(s=subject),
            "answer": answer,
            "answerType": template.answer_type,
            "wikiTitle": title,
            # Sitelinks stand in for difficulty: the more languages carry an article, the more
            # likely a learner has already met it.
            "difficulty": 1 if links >= 100 else 2 if links >= 40 else 3,
            "imageUrl": commons_image(row.get("img", {}).get("value")),
            "pageUrl": f"https://en.wikipedia.org/wiki/{urllib.parse.quote(title.replace(' ', '_'))}",
            "importance": links,
        })
    return facts


# --- Wikipedia extracts ------------------------------------------------------------------

def resolve_titles(payload):
    """Map every title we asked for onto the title the API answered under.

    `redirects=1` and MediaWiki's own normalisation both rewrite titles, so a naive lookup by
    requested title misses exactly the articles that needed redirecting — silently, as a fact
    with no details rather than as an error.
    """
    query = payload.get("query", {})
    alias = {}
    for kind in ("normalized", "redirects"):
        for hop in query.get(kind, []):
            alias[hop["from"]] = hop["to"]

    def final(title, depth=0):
        return title if title not in alias or depth > 4 else final(alias[title], depth + 1)

    return final


def extracts_for(titles):
    """Wikipedia's TextExtracts, in batches, following the continue cursor."""
    found = {}
    unique = list(dict.fromkeys(t for t in titles if t))
    for start in range(0, len(unique), 20):
        chunk = unique[start:start + 20]
        params = {
            "action": "query", "format": "json", "prop": "extracts",
            "exintro": 1, "explaintext": 1, "exlimit": "max",
            "titles": "|".join(chunk), "redirects": 1,
        }
        cont = {}
        by_final = {}
        resolver = None
        for _ in range(6):
            request = urllib.request.Request(
                f"{WIKI_API}?{urllib.parse.urlencode({**params, **cont})}",
                headers={"User-Agent": UA})
            try:
                with hard_timeout(45):
                    with urllib.request.urlopen(request, timeout=40) as response:
                        payload = json.load(response)
            except Exception:
                break
            resolver = resolver or resolve_titles(payload)
            for page in payload.get("query", {}).get("pages", {}).values():
                text = (page.get("extract") or "").strip()
                if text:
                    by_final[page["title"]] = text[:DETAIL_CHARS]
            if "continue" not in payload:
                break
            cont = payload["continue"]

        if resolver:
            for title in chunk:
                text = by_final.get(resolver(title))
                if text:
                    found[title] = text
    return found


# --- Publishing --------------------------------------------------------------------------

def validate(facts):
    """Refuse to publish a corpus that would make bad quizzes."""
    problems = []
    ids = [f["id"] for f in facts]
    if len(ids) != len(set(ids)):
        problems.append("duplicate fact ids")

    by_type = {}
    for fact in facts:
        by_type.setdefault(fact["answerType"], set()).add(fact["answer"])
    for answer_type, answers in sorted(by_type.items()):
        # The quiz draws three distractors from the same answerType; fewer than four distinct
        # answers means it cannot build a question without offering the right one twice.
        if len(answers) < 4:
            problems.append(f"answerType '{answer_type}' has only {len(answers)} distinct answers")

    for field in ("title", "statement", "question", "answer", "answerType"):
        blank = sum(1 for f in facts if not str(f.get(field, "")).strip())
        if blank:
            problems.append(f"{blank} facts have a blank {field}, which the parser rejects")

    bad = sum(1 for f in facts if f["difficulty"] not in (1, 2, 3))
    if bad:
        problems.append(f"{bad} facts have a difficulty outside 1..3")
    return problems


def previous_total():
    index = OUT / "index.json"
    if not index.exists():
        return 0
    try:
        return sum(s.get("facts", 0) for s in json.loads(index.read_text()).get("shards", []))
    except Exception:
        return 0


def publish(by_category):
    """Write the shards and the index the device reads to top itself up."""
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.json"):
        old.unlink()

    shards = []
    for category, facts in sorted(by_category.items()):
        facts.sort(key=lambda f: (-f["importance"], f["id"]))
        chunks = [facts[i:i + PER_SHARD] for i in range(0, len(facts), PER_SHARD)]
        for number, chunk in enumerate(chunks):
            pack_id = f"library-{category}-{number:03d}"
            body = {
                "category": category,
                "packId": pack_id,
                "name": f"{CATEGORIES[category]} · more facts",
                "version": hashlib.sha1(
                    json.dumps(chunk, sort_keys=True).encode()).hexdigest()[:12],
                "facts": chunk,
            }
            name = f"{category}-{number:03d}.json"
            raw = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
            (OUT / name).write_text(raw)
            shards.append({
                "id": pack_id,
                "name": body["name"],
                "category": category,
                "version": body["version"],
                "facts": len(chunk),
                "bytes": len(raw.encode()),
                # Relative to packs/, which is what PackService appends to its base URL.
                "file": f"library/{name}",
                # The device takes shards in this order, so a learner meets the most-linked
                # facts of a category before its obscure ones.
                "shard": number,
            })
        print(f"  {category:<12} {len(facts):>5} facts in {len(chunks)} shards")

    (OUT / "index.json").write_text(json.dumps({
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "shards": shards,
    }, indent=1))
    return shards


# --- Self-test ---------------------------------------------------------------------------

def self_test():
    """Everything that does not need the network, checked wherever this runs.

    The sandbox this is written in cannot reach Wikidata or Wikipedia, so without this the
    first evidence that any of the logic works would be a CI run that has already deleted the
    published library.
    """
    failures = []

    def check(name, condition):
        print(f"  {'ok  ' if condition else 'FAIL'} {name}")
        if not condition:
            failures.append(name)

    typo = Template("t", "science", "x", "{s}", "{s}?", "{s}.", "?s wdt:P31 wd:Q3valid ; wdt:P1103x ?o .")
    check("a malformed id is caught with no network", malformed_ids(typo) == ["Q3valid", "P1103x"])
    check("real ids pass the shape test", malformed_ids(TEMPLATES[0]) == [])
    check("every shipped template names only well-formed ids",
          all(not malformed_ids(t) for t in TEMPLATES))
    check("preflight drops a template it cannot trust", preflight([typo], offline=True) == [])
    check("ids are extracted from both wd: and wdt: prefixes, in order",
          referenced_ids(TEMPLATES[0]) == ["P31", "Q6256", "P36"])

    template = TEMPLATES[0]

    def row(qid, subject, answer, sl="120", img=None, article="https://en.wikipedia.org/wiki/X"):
        built = {"s": {"value": f"http://www.wikidata.org/entity/{qid}"},
                 "sLabel": {"value": subject}, "oLabel": {"value": answer},
                 "sl": {"value": sl}}
        if img:
            built["img"] = {"value": img}
        if article:
            built["article"] = {"value": article}
        return built

    facts = make_facts(template, [row("Q142", "France", "Paris")])
    check("a clean row becomes a fact", len(facts) == 1 and facts[0]["answer"] == "Paris")
    check("the fact carries the fields the parser requires",
          all(facts[0].get(f) for f in
              ("id", "title", "statement", "question", "answer", "answerType")))
    check("the question reads as written", facts[0]["question"] == "What is the capital of France?")

    check("an unresolved label is dropped",
          make_facts(template, [row("Q1", "Q1", "Paris")]) == [])
    check("a subject with two answers is dropped as ambiguous",
          make_facts(template, [row("Q1", "Panama", "Balboa"), row("Q1", "Panama", "Dollar")]) == [])
    check("a subject repeated with one answer survives once",
          len(make_facts(template, [row("Q1", "France", "Paris"), row("Q1", "France", "Paris")])) == 1)
    check("an item with no Wikipedia article is dropped",
          make_facts(template, [row("Q1", "Obscure", "Thing", article=None)]) == [])
    check("a decorated row wins over a bare one",
          make_facts(template, [row("Q1", "France", "Paris"),
                                row("Q1", "France", "Paris",
                                    img="http://commons.wikimedia.org/wiki/Special:FilePath/P.jpg")
                                ])[0]["imageUrl"] is not None)

    check("difficulty tracks importance",
          [make_facts(template, [row("Q1", "S", "A", sl=s)])[0]["difficulty"]
           for s in ("300", "50", "5")] == [1, 2, 3])
    check("difficulty always lands in 1..3",
          all(1 <= make_facts(template, [row("Q1", "S", "A", sl=s)])[0]["difficulty"] <= 3
              for s in ("0", "1", "39", "40", "99", "100", "9999")))

    check("a Commons url becomes a thumbnail url",
          commons_image("http://commons.wikimedia.org/wiki/Special:FilePath/Mona%20Lisa.jpg")
          == "https://commons.wikimedia.org/wiki/Special:FilePath/Mona%20Lisa.jpg?width=800")
    check("no image stays no image", commons_image(None) is None)
    check("a title round-trips out of an article url",
          wiki_title("https://en.wikipedia.org/wiki/Notre-Dame_de_Paris") == "Notre-Dame de Paris")

    resolver = resolve_titles({"query": {
        "normalized": [{"from": "mona lisa", "to": "Mona lisa"}],
        "redirects": [{"from": "Mona lisa", "to": "Mona Lisa"}],
    }})
    check("a redirected title is followed to where the extract actually is",
          resolver("mona lisa") == "Mona Lisa")
    check("an untouched title is left alone", resolver("Paris") == "Paris")

    thin = [{"id": f"x{i}", "title": "t", "statement": "s", "question": "q",
             "answer": "same", "answerType": "capital", "difficulty": 1} for i in range(5)]
    check("a corpus with too few distinct answers is refused",
          any("distinct answers" in p for p in validate(thin)))
    check("duplicate ids are refused",
          any("duplicate" in p for p in validate(thin + [dict(thin[0])])))
    varied = [dict(f, id=f"y{i}", answer=f"a{i}") for i, f in enumerate(thin)]
    check("a healthy corpus passes", validate(varied) == [])
    check("a blank required field is refused",
          any("blank answer" in p for p in validate(varied + [dict(varied[0], id="z", answer=" ")])))

    wide = Template("w", "science", "x", "{s}", "{s}?", "{s}.", "?s wdt:P31 ?o .", floor=0)
    narrow = Template("n", "science", "x", "{s}", "{s}?", "{s}.", "?s wdt:P31 ?o .", floor=120)
    check("no template is attempted more than three times",
          all(len(attempt_floors(t)) <= MAX_ATTEMPTS for t in TEMPLATES + [wide, narrow]))
    check("the last attempt is the narrowest question available",
          attempt_floors(wide)[-1] == FLOOR_LADDER[-1])
    check("the first attempt honours the template's own floor",
          attempt_floors(wide)[0] == 0 and attempt_floors(narrow)[0] == 120)
    check("attempts never loosen the floor",
          all(attempt_floors(t) == sorted(attempt_floors(t)) for t in TEMPLATES))

    # The bound that matters: urlopen's own timeout is per-socket-read, so a slow trickle of
    # bytes escapes it entirely. This is what stops one query eating the whole run.
    started = time.monotonic()
    try:
        with hard_timeout(0.2):
            time.sleep(5)
        timed_out = False
    except Overtime:
        timed_out = True
    elapsed = time.monotonic() - started
    check("a request that overruns its wall clock is cut off", timed_out and elapsed < 1.0)

    with hard_timeout(30):
        pass
    check("the alarm is disarmed once the request returns",
          signal.getitimer(signal.ITIMER_REAL)[0] == 0.0)

    check("a query ranks before it decorates",
          build_query(TEMPLATES[0], 0, 5).index("ORDER BY DESC(?sl)")
          < build_query(TEMPLATES[0], 0, 5).index("OPTIONAL"))
    check("every category maps to one the app knows",
          {t.category for t in TEMPLATES} <= set(CATEGORIES))
    check("template keys are unique", len({t.key for t in TEMPLATES}) == len(TEMPLATES))

    print(f"\n{len(failures)} failed" if failures else "\nAll checks passed.")
    return 1 if failures else 0


# --- Main --------------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true",
                        help="run every network-free check and exit")
    parser.add_argument("--limit", type=int, default=MAX_PER_TEMPLATE,
                        help="facts per template (a small value makes a fast smoke run)")
    parser.add_argument("--dry-run", action="store_true",
                        help="harvest and report, but publish nothing")
    parser.add_argument("--budget-minutes", type=float, default=BUDGET_MINUTES,
                        help="stop starting new templates after this long")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    print(f"Checking the {len(TEMPLATES)} templates' Wikidata ids.\n")
    templates = preflight(TEMPLATES)
    if not templates:
        print("\nFAIL: no template survived preflight. Leaving the library untouched.")
        return 1

    print(f"\nHarvesting {len(templates)} templates, up to {args.limit} each, "
          f"within {args.budget_minutes} minutes.\n")
    deadline = time.monotonic() + args.budget_minutes * 60
    by_category = {}
    skipped = 0
    for template in templates:
        if time.monotonic() > deadline:
            print(f"  {template.key:<20}    - skipped, out of time")
            skipped += 1
            continue
        rows, note = harvest(template, args.limit)
        facts = make_facts(template, rows)
        images = sum(1 for f in facts if f["imageUrl"])
        print(f"  {template.key:<20} {len(facts):>4} facts, {images:>4} with images  [{note}]")
        by_category.setdefault(template.category, []).extend(facts)
        time.sleep(1)

    if skipped:
        print(f"\n{skipped} templates never ran. The size guards below decide whether what "
              f"was collected is still worth publishing.")

    total = sum(len(v) for v in by_category.values())
    previous = previous_total()
    if total < MIN_TOTAL or (previous and total < previous * MIN_FRACTION_OF_PREVIOUS):
        print(f"\nFAIL: {total} facts is too few (floor {MIN_TOTAL}, "
              f"previously published {previous}). Leaving the library untouched.")
        return 1

    # Details make the Learn screen worth reading; fetched once here rather than on the phone.
    print("\nFetching Wikipedia extracts...")
    for category, facts in sorted(by_category.items()):
        extracts = extracts_for([f["wikiTitle"] for f in facts])
        hits = 0
        for fact in facts:
            fact["details"] = extracts.get(fact["wikiTitle"])
            hits += 1 if fact["details"] else 0
        print(f"  {category:<12} {hits}/{len(facts)} extracts")

    all_facts = [f for facts in by_category.values() for f in facts]
    problems = validate(all_facts)
    if problems:
        print("\nFAIL — refusing to publish:")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    if args.dry_run:
        print(f"\nDry run: {total} facts would have been published.")
        return 0

    print()
    shards = publish(by_category)
    size = sum(s["bytes"] for s in shards)
    print(f"\n{total} facts in {len(shards)} shards, {size // 1024} KB "
          f"({size // max(total, 1)} bytes per fact). The app bundles 517.")
    print("Shards are downloaded on demand, so the APK does not grow.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
