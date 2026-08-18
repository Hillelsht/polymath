# Smart

An Android app that teaches you general knowledge until you are genuinely good at trivia.

**[Download `smart.apk`](https://github.com/Hillelsht/polymath/releases/latest)** on your phone and
tap to install (allow installs from your browser if asked). Android 8.0+. Every push to `main`
refreshes that release automatically.

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

One honest caveat: this builds knowledge, which is exactly what trivia rewards. It will not raise
raw IQ — nothing does through an app.

## The loop

1. **Learn** — new facts arrive as full-bleed cards: image, the fact, and a memory hook.
2. **Review** — facts return for active recall on an SM-2 schedule. You try, reveal, then grade
   yourself Forgot / Hard / Good / Easy, which sets the next interval.
3. **Watch** — educational videos from an allowlist of channels, each ending in *Quiz me on this*.
4. **Play** — five games that draw on what you've learned. Miss a question in any of them and it
   enters your review queue.
5. **Progress** — streaks, per-subject mastery rings, lifetime quiz accuracy.

## Curriculum

**517 hand-written facts across six categories** — Geography, History, Science, Arts &
Literature, Pop Culture, Sports & Games — bundled in the APK, plus **3,500+ more** generated from
Wikidata and downloaded on demand as you exhaust each subject. The app never runs out.

Adding knowledge never requires touching Kotlin. The JSON is the authoring format:

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
answers of the same type, so a question about a capital city is never offered a chemical element
as an alternative.

The generated library takes this further — a fact from Wikidata is a *property*, not a sentence
("capital of France" is P36 on Q142), so it is true by construction rather than by parsing, and
the property itself supplies the answer type.

## Languages

English, **Русский** and **עברית**, switchable in Settings — UI, trivia content, and the Watch
channel list all follow. Hebrew lays out right-to-left.

## The games

- **The Climb** — a roguelike tower. Fight upward on what you know, collect relics, push your
  luck. A run is 20–40 minutes.
- **Chains** — sixteen tiles, four hidden groups. One grid a day, the same one for everybody.
- **Gambit** — chess against an engine that gets weaker the more you answer. Bank *tempo* between
  moves and spend it on a hint, a takeback, or a level of the engine's search depth.
- **The Vaults** — a side-scrolling descent in the *Prince of Persia* tradition. Ledge grabs,
  floors that give way, blades on a cycle. No questions: just the descent, against one clock.
- **Quiz** — ten questions on what you've been studying.

Every game is drawn with Compose vectors rather than sprite sheets, so none of them grow the APK.

## Watch — a walled garden

Videos come from an allowlist of channels, restricted to the same six subjects. No YouTube
search, no recommendation feed, no comments, no end-screen suggestions.

**Video ids are never written by hand.** The allowlist names *channels*; CI resolves each handle
to its channel id, verifies the channel permits embedding, and the app then reads real uploads
from that channel's public feed. Every id is real by construction, and the shelf refreshes itself
as those channels publish. (The first attempt did hand-author ids from memory: CI found that 30
of 187 existed, and most of those pointed at unrelated videos. Hence discovery rather than
guessing.)

**What this is not:** Smart cannot restrict the separate YouTube app, or anything else on the
device — no app can. That is device-level parental control. What this tab does is make the good
option the easy one, inside an app that has nothing else in it. Playback uses YouTube's official
embedded player, so ads still play and YouTube's branding still appears.

## Architecture

Single-module Kotlin app, MVVM, Jetpack Compose + Material 3, Room.

```
app/src/main/java/com/hillelsht/smart/
  domain/   Pure Kotlin, zero Android imports — the learning engine and every game's rules
  data/     Room storage, remote content, Wikipedia image resolution, one repository
  ui/       Compose screens: today · read · watch · play · progress · settings
enginetests/  Standalone JVM build that compiles the real domain sources and tests them
tools/        Python content pipelines
packs/        Generated content, served to installed apps without an app update
```

**Content ships without app updates.** Facts, the channel allowlist, video durations, game relics
and daily puzzles are all served from `packs/` over raw.githubusercontent.com. Pushing content
updates every installed app; the APK never grows, because images stream from Wikipedia into a
bounded 256 MB on-device cache.

**Colour is deliberately not Material You.** Category identity (Geography teal, History amber,
Science blue) is the app's main navigational cue, and repainting it from the user's wallpaper
would destroy it.

## Building and testing

```bash
./gradlew assembleDebug        # requires a local Android SDK
gradle -p enginetests test     # 292 tests, no SDK or emulator needed
```

The domain layer has no Android dependencies, so it is tested by a standalone JVM build pointed
at the app's *real* sources — these tests exercise the exact code that ships. They cover the SM-2
scheduler, the quiz generator's distractor selection, session planning, streaks and mastery, the
full published curriculum, video curation, and every game's rules — including a `perft` suite
that verifies the chess move generator against published node counts, exactly.

Contributor and agent documentation lives in [`docs/`](docs/), indexed from
[`CLAUDE.md`](CLAUDE.md).
