#!/usr/bin/env python3
"""Decide whether a pack is one this app can actually teach from.

Every other tool here *writes* content. This one only reads it, and it exists because the next
source of content is not going to be a pipeline in this repository — it is going to be someone
else, opening a pull request with a JSON file about a subject nobody here knows anything about.
That is the point of Wedge 3 and it needs a contract that can be checked by a machine, because
"looks fine to me" does not scale past the first contributor.

**What it checks is not "does this parse".** `ContentParser` already refuses a fact with a blank
question, and `enginetests` already runs it over everything committed. The interesting failures
are the ones that parse perfectly and still make a bad pack:

  * A question containing its own answer. The quiz prints the answer inside the question and marks
    three distractors wrong for no reason — a fact that actively teaches the wrong lesson about
    whether you knew something.
  * An `answerType` with three answers in it. `QuizGenerator` draws distractors from facts sharing
    an answerType, so a type that thin offers the same two options every time it comes up.
  * A translated fact reusing an English fact's id. Installing it **overwrites the English fact and
    takes its review history with it** — silently, on a device, with no way back. This is the one
    failure in the list that destroys something rather than annoying someone.

Errors mean the pack would misbehave; warnings mean it would work and could be better. `--strict`
promotes warnings, which is what CI runs.

Stdlib only, no network, self-testing — like every tool in this directory, so a contributor can
run it before opening the pull request and get the same answer CI will.

    python3 tools/validate_pack.py                      # everything committed
    python3 tools/validate_pack.py packs/community/     # one directory
    python3 tools/validate_pack.py --self-test
"""

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Kept in step with `Category` and `Language` by `PackContractTest`, which reads the enums rather
# than a copy of them — a list here that drifts from the app is a validator that passes a pack the
# device then refuses.
CATEGORIES = {"geography", "history", "science", "arts", "sports", "culture"}
LANGUAGES = {"en", "ru", "he"}
DEFAULT_LANGUAGE = "en"

FILE_KEYS = {"category", "facts", "packId", "version", "name", "language"}
FACT_KEYS = {
    "id", "title", "statement", "question", "answer", "answerType",
    "hook", "wikiTitle", "difficulty", "details", "imageUrl", "pageUrl",
    # Written by `generate_facts.py` as its sort key — how many language Wikipedias carry an
    # article — and deliberately not read on the device. Known-and-ignored rather than unknown,
    # or every generated fact in the repository would warn about it.
    "importance",
}
REQUIRED_FACT_KEYS = ("id", "title", "statement", "question", "answer", "answerType")

# The quiz needs three distractors plus the answer, and it draws them from facts sharing an
# answerType. Four is the arithmetic floor; eight is the point at which a type stops offering the
# same three wrong answers every time. Both numbers are `generate_facts.py`'s, deliberately: a
# hand-authored pack should not be held to a lower bar than a generated one.
MIN_DISTINCT_ANSWERS = 4
MIN_PER_ANSWER_TYPE = 8


class Report:
    """Findings for one run, kept as text rather than exceptions so a bad pack reports all of it."""

    def __init__(self):
        self.errors = []
        self.warnings = []

    def error(self, where, message):
        self.errors.append(f"{where}: {message}")

    def warn(self, where, message):
        self.warnings.append(f"{where}: {message}")

    @property
    def ok(self):
        return not self.errors

    def print(self, strict):
        for line in self.errors:
            print(f"  ERROR   {line}")
        for line in self.warnings:
            print(f"  {'ERROR  ' if strict else 'warning'} {line}")
        return not self.errors and not (strict and self.warnings)


def contains_answer(question, answer):
    """Whether [question] gives [answer] away.

    Word boundaries, not `in`: "Which continent is Australia in?" answers "Australia" and is a
    perfectly good fact, while "Chad" inside "Lake Chad" is not the same word twice. Matching on
    substrings would flag the second and matching on nothing would miss neither, so this errs
    toward reporting and lets `--strict` decide whether that stops a build.
    """
    if not answer.strip():
        return False
    pattern = r"(?<!\w)" + re.escape(answer.strip()) + r"(?!\w)"
    return re.search(pattern, question, re.IGNORECASE | re.UNICODE) is not None


def label_for(path):
    """A path the reader can act on. Relative to the repository where possible, else as given."""
    try:
        return str(Path(path).resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def read_pack(path, report):
    """One file, checked against everything `ContentParser` enforces and a few things it does not."""
    where = label_for(path)
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        report.error(where, f"cannot be read ({exc})")
        return None
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        report.error(where, f"is not valid JSON ({exc})")
        return None
    if not isinstance(data, dict):
        report.error(where, "is not a JSON object")
        return None

    # `packs/` also holds the Watch allowlist, the video durations, the pack manifest and the
    # games' own content, none of which is a fact pack and none of which should be reported as a
    # broken one. A file claiming neither half of the shape is not this tool's business; a file
    # claiming one half and not the other is a fact pack somebody broke.
    if "category" not in data and "facts" not in data:
        return None

    category = data.get("category")
    if category not in CATEGORIES:
        report.error(where, f"declares category {category!r}, which the app has no shelf for")

    language = data.get("language", DEFAULT_LANGUAGE)
    if language not in LANGUAGES:
        report.error(where, f"declares language {language!r}, which the app cannot present")
        language = DEFAULT_LANGUAGE

    pack_id = data.get("packId") or category
    suffix = "" if language == DEFAULT_LANGUAGE else f"-{language}"
    if suffix and isinstance(pack_id, str) and not pack_id.endswith(suffix):
        report.error(
            where,
            f"is in {language} but its packId {pack_id!r} has no {suffix!r} suffix — installing it "
            f"would replace the English pack of that id",
        )

    for key in set(data) - FILE_KEYS:
        report.warn(where, f"has unknown key {key!r}, which the app ignores silently")

    facts = data.get("facts")
    if not isinstance(facts, list) or not facts:
        report.error(where, "has no facts")
        return None

    seen_ids = set()
    kept = []
    for index, fact in enumerate(facts):
        label = f"{where} fact {index}"
        if not isinstance(fact, dict):
            report.error(label, "is not an object")
            continue

        missing = [k for k in REQUIRED_FACT_KEYS
                   if not isinstance(fact.get(k), str) or not fact[k].strip()]
        if missing:
            report.error(label, f"has blank or missing {', '.join(missing)}")
            continue

        label = f"{where} '{fact['id']}'"
        for key in set(fact) - FACT_KEYS:
            report.warn(label, f"has unknown key {key!r}, which the app ignores silently")

        difficulty = fact.get("difficulty", 1)
        if not isinstance(difficulty, int) or isinstance(difficulty, bool) or difficulty not in (1, 2, 3):
            report.error(label, f"has difficulty {difficulty!r}, expected 1, 2 or 3")

        if fact["id"] in seen_ids:
            report.error(label, "appears twice in this file, so one copy silently wins")
        seen_ids.add(fact["id"])

        if suffix and not fact["id"].endswith(suffix):
            report.error(
                label,
                f"is a {language} fact with no {suffix!r} suffix — installing this pack would "
                f"overwrite the English fact of that id and destroy its review history",
            )
        if not suffix:
            for tag in LANGUAGES - {DEFAULT_LANGUAGE}:
                if fact["id"].endswith(f"-{tag}"):
                    report.error(label, f"is an English fact wearing a {tag!r} id suffix")

        if contains_answer(fact["question"], fact["answer"]):
            report.error(
                label,
                f"asks {fact['question']!r}, which already contains the answer {fact['answer']!r}",
            )
        if not contains_answer(fact["statement"], fact["answer"]):
            report.warn(label, "has a statement that never says the answer, so it teaches nothing")

        if not fact.get("wikiTitle"):
            report.warn(label, "has no wikiTitle, so enrichment has nothing to look up")
        for key in ("imageUrl", "pageUrl"):
            value = fact.get(key)
            if value and not str(value).startswith("https://"):
                report.error(label, f"has a non-https {key}")

        kept.append(fact)

    return {"path": path, "packId": pack_id, "language": language, "facts": kept}


def check_corpus(packs, report):
    """The checks that need more than one file: the quiz's needs, and ids that collide.

    Every file handed to one run is treated as one corpus, because that is how the app pools —
    `QuizGenerator` draws distractors from everything installed, not from the shard the fact came
    out of. Validating a single file on its own therefore holds it to a *stricter* bar than the
    device does, which is the right way round for someone about to publish one.
    """
    by_id = defaultdict(list)
    by_question = defaultdict(list)
    by_type = defaultdict(list)

    for pack in packs:
        for fact in pack["facts"]:
            by_id[fact["id"]].append(label_for(pack["path"]))
            by_question[(pack["language"], fact["question"].strip().lower())].append(fact["id"])
            by_type[(pack["language"], fact["answerType"])].append(fact["answer"])

    collisions = {i: sorted(set(f)) for i, f in by_id.items() if len(f) > 1}
    # Reported as one finding per pair of files rather than one per id: the authoring sources under
    # `assets/content/` and their enriched copies under `packs/` are the same facts, so pointing
    # both at one run produces a thousand identical complaints and no information.
    by_pair = defaultdict(list)
    for fact_id, files in collisions.items():
        by_pair[tuple(files)].append(fact_id)
    for files, ids in sorted(by_pair.items()):
        report.error(
            "corpus",
            f"{len(ids)} fact id(s) are used by more than one file — {' and '.join(files)} "
            f"— starting with {', '.join(sorted(ids)[:3])}",
        )

    for (_, question), ids in sorted(by_question.items()):
        if len(ids) > 1:
            report.warn("corpus", f"{len(ids)} facts ask the same question: {', '.join(sorted(ids)[:3])}")

    for (language, answer_type), answers in sorted(by_type.items()):
        distinct = len({a.strip().lower() for a in answers})
        tag = f"{answer_type!r} ({language})"
        if distinct < MIN_DISTINCT_ANSWERS:
            report.error(
                "corpus",
                f"answerType {tag} has only {distinct} distinct answers — the quiz needs "
                f"{MIN_DISTINCT_ANSWERS} to offer four options",
            )
        elif len(answers) < MIN_PER_ANSWER_TYPE:
            report.warn(
                "corpus",
                f"answerType {tag} has {len(answers)} facts — thin enough that the same wrong "
                f"answers will come round every time",
            )


def files_under(paths):
    out = []
    for raw in paths:
        path = Path(raw)
        if path.is_dir():
            out.extend(sorted(p for p in path.rglob("*.json") if p.name != "index.json"))
        elif path.is_file():
            out.append(path)
        else:
            print(f"  ERROR   {raw}: no such file or directory")
            out.append(None)
    return out


def validate(paths, strict=False, quiet=False):
    report = Report()
    packs = []
    files = files_under(paths)
    if any(f is None for f in files):
        return 1
    if not files:
        print("Nothing to validate.")
        return 1

    for path in files:
        pack = read_pack(path, report)
        if pack:
            packs.append(pack)
    check_corpus(packs, report)

    total = sum(len(p["facts"]) for p in packs)
    if not quiet:
        skipped = len(files) - len(packs) - len({e.split(":")[0] for e in report.errors})
        print(f"{len(packs)} fact pack(s), {total} facts" +
              (f", {skipped} other file(s) skipped" if skipped > 0 else ""))
    ok = report.print(strict)
    if not quiet:
        print("OK" if ok else f"\n{len(report.errors)} error(s), {len(report.warnings)} warning(s)")
    return 0 if ok else 1


# --- self-test ----------------------------------------------------------------------------------

def self_test():
    failures = []

    def check(name, condition):
        print(f"  {'ok  ' if condition else 'FAIL'} {name}")
        if not condition:
            failures.append(name)

    def fact(**over):
        base = dict(
            id="t-1", title="A title", statement="The capital of France is Paris.",
            question="What is the capital of France?", answer="Paris",
            answerType="capital", wikiTitle="Paris", difficulty=1,
        )
        base.update(over)
        return base

    def run(data, strict=False):
        report = Report()
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pack.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            pack = read_pack(path, report)
        return report, pack

    good = {"category": "geography", "facts": [fact()]}

    report, pack = run(good)
    check("a well-formed pack passes", report.ok and pack and len(pack["facts"]) == 1)

    check("an unknown category is refused",
          not run({**good, "category": "gardening"})[0].ok)
    check("an unknown language is refused",
          not run({**good, "language": "fr"})[0].ok)
    check("a pack with no facts is refused",
          not run({"category": "geography", "facts": []})[0].ok)
    not_a_pack = run({"channels": []})
    check("a file that is not a fact pack at all is skipped, not failed",
          not_a_pack[1] is None and not_a_pack[0].ok and not not_a_pack[0].warnings)
    check("a fact pack missing its facts is still failed, not skipped",
          not run({"category": "geography"})[0].ok)
    check("a blank question is refused",
          not run({**good, "facts": [fact(question="  ")]})[0].ok)
    check("a missing answer is refused",
          not run({**good, "facts": [{k: v for k, v in fact().items() if k != "answer"}]})[0].ok)
    check("difficulty outside 1..3 is refused",
          not run({**good, "facts": [fact(difficulty=4)]})[0].ok)
    check("a boolean difficulty is refused, not read as 1",
          not run({**good, "facts": [fact(difficulty=True)]})[0].ok)
    check("a repeated id inside one file is refused",
          not run({**good, "facts": [fact(), fact()]})[0].ok)

    # The answer-in-the-question rule, and the reason it is worded on word boundaries.
    check("a question containing its own answer is refused",
          not run({**good, "facts": [
              fact(question="Is the capital of France Paris?")]})[0].ok)
    check("the answer as part of a longer word is not the answer",
          run({**good, "facts": [fact(
              question="Which country contains Parisian suburbs?", answer="Paris",
              statement="Paris is there.")]})[0].ok)
    check("case does not hide the answer",
          not run({**good, "facts": [fact(question="Is it PARIS?")]})[0].ok)

    # The id-suffix rule, which is the one that destroys data rather than annoying anyone.
    ru = {
        "category": "geography", "language": "ru", "packId": "geography-ru",
        "facts": [fact(id="t-1-ru", question="Столица Франции?", answer="Париж",
                       statement="Столица Франции — Париж.")],
    }
    check("a Russian pack with suffixed ids passes", run(ru)[0].ok)
    check("a Russian pack whose packId has no suffix is refused",
          not run({**ru, "packId": "geography"})[0].ok)
    check("a Russian fact whose id has no suffix is refused",
          not run({**ru, "facts": [fact(id="t-1", question="Столица?", answer="Париж",
                                        statement="Столица Франции — Париж.")]})[0].ok)
    check("an English fact wearing a language suffix is refused",
          not run({**good, "facts": [fact(id="t-1-ru")]})[0].ok)

    check("a non-https image is refused",
          not run({**good, "facts": [fact(imageUrl="http://example.com/a.png")]})[0].ok)
    check("an unknown field warns rather than failing",
          run({**good, "facts": [fact(imgUrl="x")]})[0].warnings)

    # Corpus checks.
    report = Report()
    thin = [{"path": Path("a.json"), "packId": "p", "language": "en",
             "facts": [fact(id=f"t-{i}", answer=f"A{i}") for i in range(3)]}]
    check_corpus(thin, report)
    check("an answerType with three answers is refused", not report.ok)

    report = Report()
    enough = [{"path": Path("a.json"), "packId": "p", "language": "en",
               "facts": [fact(id=f"t-{i}", answer=f"A{i}", question=f"Q{i}?") for i in range(8)]}]
    check_corpus(enough, report)
    check("eight distinct answers pass without a warning", report.ok and not report.warnings)

    report = Report()
    check_corpus([{"path": Path("a.json"), "packId": "p", "language": "en",
                   "facts": [fact(id=f"t-{i}", answer=f"A{i}") for i in range(8)]}], report)
    check("the same question asked eight times is worth a warning", report.warnings)

    report = Report()
    check_corpus([
        {"path": Path("a.json"), "packId": "p", "language": "en",
         "facts": [fact(id="dup", answer=f"A{i}") for i in range(4)]},
        {"path": Path("b.json"), "packId": "q", "language": "en",
         "facts": [fact(id="dup", answer=f"B{i}") for i in range(4)]},
    ], report)
    check("the same id in two files is refused", not report.ok)

    print(f"\n{len(failures)} failed" if failures else "\nAll checks passed.")
    return 1 if failures else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="*", help="files or directories (default: everything committed)")
    parser.add_argument("--self-test", action="store_true", help="run the offline checks and exit")
    parser.add_argument("--strict", action="store_true", help="treat warnings as errors")
    parser.add_argument("--quiet", action="store_true", help="print findings only")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    paths = args.paths or [str(ROOT / "packs")]
    return validate(paths, strict=args.strict, quiet=args.quiet)


if __name__ == "__main__":
    sys.exit(main())
