#!/usr/bin/env python3
"""Can Wikidata supply an unlimited stream of good facts?

The Library is 517 facts because 517 seeds were hand-written. Delivery was never the limit —
the app has downloaded packs from the repo since v2 — authorship is. So the question is whether
a machine-readable source can supply correct, *important* facts at scale.

Wikidata is the candidate, and it is a better fit than it first looks:

  * Facts are structured, not prose. "Capital of Australia" is property P36 on item Q408, so a
    generated fact is true by construction rather than by parsing a sentence. That matters here:
    earlier in this project 187 hand-written YouTube ids turned out to contain only 30 real ones.
  * A property IS an answerType. Every item with P36 yields a capital, so the quiz's distractors
    come out same-typed and plausible for free. Today the smallest answerType groups hold four
    facts, which makes thin quizzes; a property yields hundreds.
  * Importance is measurable. Sitelink count — how many language Wikipedias carry an article —
    ranks what matters, which is what "a new, also very important fact" requires.

This probe does not assume any of that works. It runs real queries and prints real rows.
"""

import json
import sys
import urllib.parse
import urllib.request

SPARQL = "https://query.wikidata.org/sparql"
UA = "SmartTriviaPipeline/1.0 (+https://github.com/hillelsht/smart) python-urllib"

# One template = one property + one question shape. ~30 of these hand-written once would cover
# the whole Library; each yields thousands of items.
TEMPLATES = [
    ("capital", "What is the capital of {subject}?",
     "?s wdt:P31 wd:Q6256 ; wdt:P36 ?o ."),
    ("painter", "Who painted {subject}?",
     "?s wdt:P31 wd:Q3305213 ; wdt:P170 ?o ."),
    ("element", "What is the chemical symbol for {subject}?",
     "?s wdt:P31 wd:Q11344 ; wdt:P246 ?o ."),
]


def ask(query, timeout=60):
    url = f"{SPARQL}?{urllib.parse.urlencode({'query': query, 'format': 'json'})}"
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response), None
    except Exception as exc:
        return None, f"{type(exc).__name__} {getattr(exc, 'code', '')}"


def sample(name, question, where, limit=5):
    query = f"""
    SELECT ?sLabel ?oLabel ?sitelinks ?img WHERE {{
      {where}
      ?s wikibase:sitelinks ?sitelinks .
      OPTIONAL {{ ?s wdt:P18 ?img }}
      SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }}
    }}
    ORDER BY DESC(?sitelinks) LIMIT {limit}
    """
    data, err = ask(query)
    if err:
        print(f"  {name}: FAILED — {err}")
        return 0
    rows = data["results"]["bindings"]
    print(f"  {name}: {len(rows)} rows, most important first")
    for row in rows:
        subject = row["sLabel"]["value"]
        answer = row["oLabel"]["value"]
        links = row["sitelinks"]["value"]
        img = "image" if "img" in row else "no image"
        print(f"      {question.format(subject=subject):<52} -> {answer:<22} "
              f"({links} wikis, {img})")
    return len(rows)


def yield_estimate(name, where):
    data, err = ask(f"SELECT (COUNT(DISTINCT ?s) AS ?n) WHERE {{ {where} }}")
    if err:
        return None
    return int(data["results"]["bindings"][0]["n"]["value"])


def main():
    print("Probing Wikidata as an unlimited fact source.\n")

    data, err = ask("SELECT ?x WHERE { BIND(1 AS ?x) }")
    if err:
        print(f"SPARQL endpoint unreachable from this runner: {err}")
        print("VERDICT: cannot generate facts this way from CI.")
        return 1
    print("SPARQL endpoint reachable.\n")

    print("Sample facts, ordered by how many language Wikipedias carry the subject:")
    ok = 0
    for name, question, where in TEMPLATES:
        ok += 1 if sample(name, question, where) else 0

    print("\nHow many facts each template could yield:")
    total = 0
    for name, _, where in TEMPLATES:
        n = yield_estimate(name, where)
        total += n or 0
        print(f"  {name:<10} {n if n is not None else 'unknown':>8} items")

    print(f"\n3 templates alone cover ~{total} facts. The Library today holds 517.")
    print("VERDICT: feasible." if ok == len(TEMPLATES) else "VERDICT: partial — see failures.")
    return 0 if ok == len(TEMPLATES) else 1


if __name__ == "__main__":
    sys.exit(main())
