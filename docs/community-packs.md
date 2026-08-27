<!-- covers: tools/validate_pack.py, tools/topic_pack.py, tools/topic_llm.py, packs/community/** -->

# Authoring a pack

*The contract for content this repository did not write. If you want to add a hundred facts about
Formula One, Byzantine emperors or the Dutch railway network, this is what the file has to look
like and how to know it is right before anyone reviews it.*

---

## The shape

A pack is one JSON file: a category header and a list of facts.

```json
{
  "category": "science",
  "packId": "astronomy-basics",
  "name": "Astronomy · the basics",
  "version": "2026-08-19",
  "language": "en",
  "facts": [
    {
      "id": "astro-001",
      "title": "The nearest star",
      "question": "Which star is closest to the Sun?",
      "answer": "Proxima Centauri",
      "answerType": "star",
      "statement": "Proxima Centauri lies 4.24 light years away and is a red dwarf.",
      "wikiTitle": "Proxima Centauri",
      "difficulty": 2
    }
  ]
}
```

| field | | |
|---|---|---|
| `category` | required | one of `geography`, `history`, `science`, `arts`, `sports`, `culture` |
| `facts` | required | at least one |
| `packId` | | how the device keys what it has installed. Defaults to the category, which is almost never what you want — pick something specific |
| `name` | | shown on the shelf |
| `version` | | change it and every device re-seeds the pack. A date works |
| `language` | | `en`, `ru` or `he`. Defaults to `en` |

Each fact:

| field | | |
|---|---|---|
| `id` | required | unique across every pack, forever. See below |
| `title` | required | the heading on the Learn card |
| `question` | required | what the quiz asks. **Must not contain the answer** |
| `answer` | required | one string, exactly as it should be read |
| `answerType` | required | what kind of thing the answer is. This is the important one |
| `statement` | required | the fact itself, as a sentence |
| `wikiTitle` | | the Wikipedia article title, so enrichment can find a picture and a passage |
| `difficulty` | | 1, 2 or 3. Defaults to 1 |
| `hook` | | a line shown after answering |
| `details`, `imageUrl`, `pageUrl` | | filled in by the enrichment pipeline. Leave them out |

Anything else is ignored silently — which is why the validator warns about unknown keys. A typo'd
`imgUrl` is not an error the app can report; it is just a picture that never appears.

## The four things that actually matter

**`answerType` is not a label, it is the quiz.** Three of the four options a player sees are the
answers of *other facts sharing this answerType*. `"star"` draws other stars; `"fact"` draws
whatever happens to be nearby and produces a question with an obviously correct answer among three
absurd ones. Give it a type that names a category of thing, and give that type **at least four
distinct answers** — eight before it stops offering the same three wrong ones every time.

**A question must never contain its own answer.** *"Who sculpted Pietà (Michelangelo)?"* prints the
answer inside the question, so someone who knows nothing scores what someone who knows everything
does. Forty of these were in this repository's own generated library before the validator existed;
they are the single most common way a true fact makes a worthless question.

**Fact ids are permanent.** They key review history — how well a person knows that fact, and when
they should next see it. Reusing an id that already exists **overwrites the other fact and takes its
review history with it**, on a device, silently, with no way back. Review history is the one thing
here that cannot be re-downloaded. Prefix your ids with something nobody else will use.

**A translated pack names its language everywhere.** A Russian pack sets `"language": "ru"`, its
`packId` carries the tag (`astronomy-basics-ru`), and every fact `id` **ends** in `-ru`
(`astro-001-ru`). Without that it is not a Russian pack — it is a pack that deletes the English
one. This is the rule the paragraph above is about, and it is why the validator treats it as an
error rather than a warning.

The `packId` only has to name the tag as a delimited segment, not necessarily at the end: the
hand-authored packs suffix it and `generate_facts.py` prefixes it (`library-ru-geography-000`), and
both are equally namespaced against the English pack of that name. Fact ids are the strict half —
the tag goes last, because that is what everything in this repository already does and what the
validator checks.

## Checking it

```bash
python3 tools/validate_pack.py path/to/your-pack.json
```

Stdlib Python, no network, nothing to install. It reports **errors** — things that would make the
pack misbehave — and **warnings**, things that would work and could be better.

CI runs it at two bars. Everything already published has to clear the errors. Anything under
`packs/community/` has to clear the warnings too, with `--strict`, because a pack arriving from
outside is at the one moment when fixing a thin `answerType` costs a single edit rather than a
migration. So run `--strict` on your own file and land it clean:

```bash
python3 tools/validate_pack.py --strict path/to/your-pack.json
```

It checks everything the app's own parser enforces, and then the things that parse perfectly and
still make a bad pack: the four rules above, ids repeated within a file or across files, questions
asked twice, non-https images, a `statement` that never says the answer.

Validate your file **on its own**, not alongside `packs/`. The tool treats everything in one run as
a single corpus, which is how the app pools distractors — so passing it your pack alone holds it to
a stricter bar than the device will, which is the right way round before you publish.

## Submitting one

Open a pull request adding the file under `packs/community/`. What a reviewer will look at, in
order: that the validator is clean, that the facts are true, and that the questions are worth
asking. The first of those is a machine's job and the reason this document exists; the other two
are not, and no amount of tooling will make them so.

## Generating one from a topic

`tools/topic_pack.py` builds a pack from a typed topic without anyone authoring facts:

```bash
python3 tools/topic_pack.py --topic "space"            # which questions it would ask
python3 tools/topic_pack.py --topic "space" --write    # harvest and write them
```

The mapper is a synonym table and word overlap against the templates' own vocabulary. It only
reaches the twenty-two questions `generate_facts.py` knows how to ask, so *"the Byzantine
succession"* routes nowhere and says so; and it cannot narrow a topic, so *"rivers of Africa"*
harvests the same rivers as *"rivers"* and tells you it ignored the word. Both limits are printed
rather than hidden.

`--llm` closes the second one. `tools/topic_llm.py` asks a model for the same two decisions — which
templates, and a SPARQL fragment narrowing them — so *"rivers of Africa"* becomes
`?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .` and harvests African rivers.

```bash
python3 tools/topic_pack.py --topic "rivers of africa" --llm     # what it would narrow to
```

**The model never writes a fact, a question or an answer.** That is the line that makes "about
anything" safe to say: every published fact is still a Wikidata claim, and a model that phrased
facts would be a model that could invent them. What it proposes passes five gates before a single
fact is harvested — a closed template list, an allowlisted triples-only grammar, well-formed ids,
a **label check** that reads the real English label and aliases for every Q- and P-number it named
and refuses any whose meaning it got wrong, and a yield floor. A narrowing that empties its
template is dropped and reported; it is never widened back to the un-narrowed query, because a
pack called *Rivers of Africa* full of European rivers is the exact bug the feature exists to fix.

Answers are cached in `tools/topic_cache.json`, committed. A topic asked once is free forever and,
more usefully, reviewable in a diff — what a model decided a topic meant is a content decision.

What it actually does, from `probe.yml`'s first real run:

| typed | mapped to |
|---|---|
| `rivers of Africa` | `river-mouth`, `?s wdt:P30 wd:Q15 .` |
| `chemistry` | `element-symbol` unnarrowed, plus `discoverer` and `named-after` both narrowed to `?s wdt:P31 wd:Q11344 .` — so it asks who discovered an *element*, not who discovered anything |
| `films by Japanese directors` | `film-director`, `?o wdt:P27 wd:Q17 .` — narrowing the *answer* rather than the subject |
| `the Byzantine succession` | nothing, and it says why: no template asks about monarchs, rulers or succession |

The last row is the honest one. A topic the catalogue cannot serve is answered with a sentence
naming what is missing, and the fix is another template in `generate_facts.py` — not a looser
mapper. Every Q- and P-number in that table was checked against its real Wikidata label before
anything was harvested, which is the whole reason a model is allowed near published content.

**A model will name the wrong entity, and the gate is what stands between that and a published
pack.** Asked about *Star Wars*, it said `Q82347` was the franchise. Wikidata calls Q82347
"America's Next Top Model, season 2". The pack would have been named Star Wars and filled with
reality-television credits. The label check refused it — and then, because that check knows the
*real* label, the refusal is handed back to the model so it can use the id it meant. A topic that
is answerable is not routed nowhere over one wrong number; a model that repeats the same wrong id
is refused for good.

**The free tier is twenty requests a day.** A topic costs one call, or two when a proposal has to
be corrected, so a handful of topics exhausts it — and the answer comes back as a 429 that looks
exactly like the transient "experiencing high demand" one. They are told apart now, because waiting
twelve seconds for a daily allowance to return is not a strategy. The cache is what makes this
survivable: a topic asked once is never asked again.

**And a model cannot produce identifiers of any kind.** Given the right entity, it guessed the
*property*: `?s wdt:P361 wd:Q462 .` — "part of Star Wars" — which is defensible and yields one
fact, where `P8345` (media franchise) links forty-seven. So properties are looked up too. When a
narrowing matches nothing, Wikidata is asked which properties actually connect that template's
subjects to that entity, commonest first, and the retry offers them as a list. The model chooses;
it does not recall.

**A correct clause can still match nothing.** The first real publish run narrowed rivers with
`?s wdt:P30 wd:Q15 .` — "on the continent of Africa", both ids verified — and it selected zero
rivers, because almost none carry P30. Wikidata's coverage is uneven and no gate that reads a
clause can see it; only the endpoint knows. So the endpoint is asked, and an empty answer sends the
clause back to the model with the count attached: `?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .` reaches the
same meaning through a property rivers do carry. A model with nothing better costs its own
template. The one thing that never happens is widening back to the un-narrowed query, because a
pack called *Rivers of Africa* full of European rivers is exactly the bug this path exists to fix.

Whatever it writes goes through the same validator at the same `--strict` bar, and a pack that
fails is deleted rather than left in the tree. `tools/build_manifest.py` then lists it in the
catalogue, which is the only thing that makes it reachable from a device at all.

## Where this fits

`docs/content-pipeline.md` describes the generated content and the workflows that publish it.
`docs/data-model.md` has the parser and the seeding path — what the device does with a pack once it
has one. The rule about ids and review history is in `docs/invariants.md`, with the story of what
broke when it was not followed.
