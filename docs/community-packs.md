<!-- covers: tools/validate_pack.py, packs/community/** -->

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

**A translated pack suffixes everything.** A Russian pack sets `"language": "ru"`, and both its
`packId` and every fact `id` end in `-ru` (`astronomy-basics-ru`, `astro-001-ru`). Without the
suffix it is not a Russian pack — it is a pack that deletes the English one. This is the rule the
paragraph above is about, and it is why the validator treats it as an error rather than a warning.

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

It is Wedge 3's front door with the clever part left out. The version everyone imagines has a
language model reading the topic and inventing a Wikidata query; this one matches the topic against
a synonym table and the templates' own vocabulary, which is enough to prove the path — topic in,
validated pack out — and small enough that the model replaces one function when it arrives.

Two limits, printed rather than hidden. It only reaches the eighteen questions
`generate_facts.py` knows how to ask, so *"the Byzantine succession"* routes nowhere and says so.
And it cannot narrow a topic: *"rivers of Africa"* harvests the same rivers as *"rivers"*, and tells
you it ignored the word.

Whatever it writes goes through the same validator at the same `--strict` bar, and a pack that
fails is deleted rather than left in the tree.

## Where this fits

`docs/content-pipeline.md` describes the generated content and the workflows that publish it.
`docs/data-model.md` has the parser and the seeding path — what the device does with a pack once it
has one. The rule about ids and review history is in `docs/invariants.md`, with the story of what
broke when it was not followed.
