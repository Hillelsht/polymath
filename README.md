# Smart

An Android app that teaches you general knowledge until you are genuinely good at trivia.

## Why this exists, and why "just play more trivia" doesn't work

The usual advice is to practise trivia a lot. That is a weak strategy, and the instinct that it
is wrong is correct. Playing quizzes only tests the facts you happen to be dealt, gives you one
unspaced exposure to each, and leaves you to forget them on the ordinary forgetting curve. You
end up with a thin, random layer of knowledge and no mechanism for keeping it.

What actually builds durable knowledge is well established:

| Principle | What the app does |
|---|---|
| **Structured curriculum** | Teaches a deliberately chosen canon, not random questions |
| **Spaced repetition** | SM-2 scheduling brings each fact back just before you'd forget it |
| **Active recall** | You retrieve the answer before seeing it — retrieval is what builds memory |
| **Dual coding** | Every fact is paired with a real image, so it is stored twice |
| **Interleaving** | Daily batches mix subjects instead of blocking one topic |
| **Testing effect** | Quiz mode simulates real conditions and feeds misses back into review |

Quizzing is the *test*, not the training. This app is the training.

One honest caveat: this builds knowledge, which is exactly what trivia rewards. It will not
raise raw IQ — nothing does through an app.

## The learning loop

1. **Learn** — new facts arrive as full-bleed cards: image, the fact, and a memory hook.
2. **Review** — facts return for active recall on an SM-2 schedule. You try, reveal, then grade
   yourself Forgot / Hard / Good / Easy, which sets the next interval.
3. **Quiz** — 10 multiple-choice questions with same-category distractors. Anything you miss is
   pushed straight back into the review queue.
4. **Progress** — streaks, per-subject mastery rings, lifetime quiz accuracy.

## Curriculum

**517 facts across six categories**, in `app/src/main/assets/content/`:

| Category | Facts |
|---|---|
| Geography | 95 |
| History | 93 |
| Science | 92 |
| Arts & Literature | 92 |
| Pop Culture | 75 |
| Sports & Games | 70 |

Adding knowledge never requires touching Kotlin — the JSON is the authoring format:

```json
{
  "id": "geo-001",
  "title": "Capital of Australia",
  "statement": "Canberra is the capital of Australia. It was purpose-built from 1913 as a compromise after Sydney and Melbourne both refused to let the other have it.",
  "question": "What is the capital of Australia?",
  "answer": "Canberra",
  "answerType": "capital",
  "hook": "Neither rival would yield, so Australia built a brand-new capital in the gap between them.",
  "wikiTitle": "Canberra",
  "difficulty": 1
}
```

`answerType` is what makes the quizzes feel handmade: distractors are drawn only from other
answers of the same type, so a question about a capital city is never offered a chemical
element as an alternative. `CurriculumTest` enforces that every answer type has at least four
distinct answers, so this can never silently degrade.

## Architecture

Single-module Kotlin app, MVVM, Jetpack Compose + Material 3.

```
app/src/main/java/com/hillelsht/smart/
  domain/     Pure Kotlin, zero Android imports — the learning engine
              Sm2Scheduler · QuizGenerator · SessionPlanner
              MasteryCalculator · StreakCalculator
  data/       Room storage, Wikipedia image resolution, repository
  ui/         Compose screens: home · learn · review · quiz · library · stats
  assets/content/   The curriculum, six JSON files
enginetests/  Standalone JVM build that compiles the real domain sources and tests them
```

**Images** are resolved at *build time* by the content pipeline into direct
`upload.wikimedia.org` thumbnail URLs shipped inside each pack, so the phone never calls a
Wikipedia API — it just loads an image URL through Coil's bounded disk cache. A runtime
resolver (Action API, with logging) covers only facts that shipped without one; offline, cards
fall back to the category gradient rather than a broken image.

**Colour** is deliberately *not* Material You dynamic theming. Category identity (Geography
teal, History amber, Science blue) is the app's main navigational cue, and repainting it from
the user's wallpaper would destroy it.

## Getting the app

**On your phone, open [github.com/Hillelsht/smart/releases/latest](https://github.com/Hillelsht/smart/releases/latest)
and download `smart.apk`.** Tap it to install (allow installs from your browser if asked).
Android 8.0+. Every push to `main` refreshes this release automatically.

## Content without app updates

Facts live as JSON packs in [`packs/`](packs/), served to installed apps via
raw.githubusercontent.com. A CI pipeline (`tools/enrich_content.py`) enriches the hand-written
files in `app/src/main/assets/content/` with a direct Wikipedia thumbnail URL, a ~10-sentence
extract, and an article link per fact. Pushing new content updates every installed app on its
next Library visit — the APK never grows, because images stream from Wikipedia's servers into
a bounded 256 MB on-device cache.

## Building it yourself

Requires Android Studio (Ladybug or newer) or a local Android SDK.

```bash
./gradlew assembleDebug        # build the APK
./gradlew installDebug         # install on a connected device
./gradlew lint                 # static analysis
```

## Testing the learning engine

The domain layer is pure Kotlin with no Android dependencies, so it is tested by a standalone
JVM build that points at the app's *real* sources — these tests exercise the exact code that
ships in the APK, and they need no Android SDK or emulator:

```bash
gradle -p enginetests test
```

41 tests covering:

- **`Sm2SchedulerTest`** — the canonical 1 → 6 → interval×ease progression, the ease
  polynomial and its 1.3 floor, lapses returning a card the same session, monotonically growing
  intervals for every passing grade, and the two-year cap.
- **`QuizGeneratorTest`** — four distinct options, correct answer always present, distractors
  confined to the same answer type, determinism under a seed.
- **`SessionPlannerTest`** — reviews before new material, the daily new-fact cap, round-robin
  across categories, most-overdue-first ordering.
- **`StreakCalculatorTest`** / **`MasteryCalculatorTest`** — streaks surviving until a full day
  is missed, mastery measured by scheduled interval rather than raw correct answers.
- **`CurriculumTest`** — parses all 517 facts through the production parser and asserts unique
  ids, no duplicate questions, an image reference on every fact, and that a clean four-option
  question can be generated for every single fact in the corpus.
