<!-- covers: app/src/main/java/com/hillelsht/smart/data/**, app/src/main/java/com/hillelsht/smart/domain/model/**, app/src/main/java/com/hillelsht/smart/domain/PackInstall.kt, app/src/main/java/com/hillelsht/smart/domain/LibraryTopUp.kt -->

# Data model

Three representations of the same knowledge, and the two boundaries between them:

```
JSON on disk / CDN  --ContentParser-->  domain Fact  --toEntity-->  Room row
```

`data/seed/` owns the first arrow, `data/local/Entities.kt` owns the second. Both directions of
both arrows live in exactly one place each, which is deliberate — see the `ChannelEntity` story
in `invariants.md` for what happened when a mapping had two implementations.

## Room

**Version 7**, database file `smart.db`, 14 entities, 6 migrations, schemas exported to
`app/schemas`.

| Migration | Adds |
|---|---|
| 1→2 | fact enrichment columns (`details`, `imageUrl`, `pageUrl`, `packId`) + the `packs` table |
| 2→3 | `videos`, `watched_videos` |
| 3→4 | `watch_channels`, `blocked_videos`, `video_durations`, `videos.position` |
| 4→5 | `game_runs`, `game_progress`, `game_daily` |
| 5→6 | `facts.language` |
| 6→7 | `watch_channels.language` |

**Every migration is additive** — see `invariants.md`. Review history is the only data here that
cannot be re-downloaded, so nothing is ever dropped or rewritten.

Two entity notes that matter more than the rest:

- **`ChannelEntity.language` has no Kotlin default, deliberately.** Removing that default is what
  forces every construction site to name a language. Read the comment on the field before
  "tidying" it.
- **`GameProgressEntity` is a key/value bag** keyed `(gameId, entryKey)`, not a column per game.
  Every game after The Climb would otherwise need its own migration to store its own unlocks, and
  a schema change per game is a schema change too many.

Mappers all live in `Entities.kt`: `FactEntity.toDomain()`, `Fact.toEntity()`,
`WatchChannel.toEntity()`, `VideoEntity.toDomain()`, `Video.toEntity()`, and the review pair.

## DAOs

Eleven interfaces in `Daos.kt`. Three carry non-obvious intent:

- **`VideoDao.replaceShelf()`** is `@Transaction` clear-then-insert, so the shelf never blinks
  empty mid-refresh. `observeAll()` orders by `position`, because the refresh interleaves
  channels and that ordering is worth nothing if the read-back leaves it to SQLite's row order.
- **`VideoDurationDao`** has two writers on purpose: `upsert` (the player measured it — wins) and
  `insertMissing` (the pipeline guessed it — never overwrites a measured value).
- **`ReviewDao.observeDueCount()`** excludes `phase = 'NEW'`. A fact never taught is not a fact
  that is due.

## The domain types

**`Fact`** is the atomic unit and is deliberately two-directional: `statement` teaches,
`question`/`answer` recalls, `hook` is the memory aid. `answerType` is the quiz's distractor key
— all facts sharing a type are plausible wrong answers for one another, which is what makes
generated quizzes feel handmade. It stays in English in every language.

**`ReviewState`** is the scheduler's memory of one fact: phase, repetitions, interval, ease,
due date, lapses, accuracy counters.

**`Category`** — six values, each carrying its own id, display name, blurb and two hex colours.
See the purity boundary in `architecture.md` for why colours are hex strings here.

**`Language`** — `en`, `ru`, `he`. `tag` is a wire format; see `localization.md`.

**`Video`** validates its YouTube id against `^[A-Za-z0-9_-]{11}$` at construction, and derives
`LengthClass` (short < 5 min, medium ≤ 15, long > 15) from minutes.

## JSON shapes

### A fact pack

Authoring files in `assets/content/` carry only what a human writes:

```json
{ "category": "geography",
  "facts": [ { "id", "title", "statement", "question", "answer",
               "answerType", "hook", "wikiTitle", "difficulty" } ] }
```

The pipeline adds `packId`, `name`, `version`, and per fact `imageUrl`, `pageUrl`, `details`, and
writes the result to `packs/` and `app/src/main/assets/packs/`.

A **translated** pack adds a top-level `"language"` and must use a suffixed `packId` and suffixed
fact ids — `geography-he`, `geo-001-he`. This is the id-collision rule; `ContentParser`'s
`SeedFile` doc comment states it, and it is the most dangerous thing in this file to get wrong.

A **generated library shard** looks the same minus `hook`, plus `importance` (the Wikidata
sitelink count that drives both difficulty and delivery order). Its ids are
`wd-<template>-<QID>`, e.g. `wd-capital-Q142`.

### Channels, durations, games

- `channels.json` — `{ id, handle, category, displayName, language? }`. `language` is **omitted
  for English** so the published file keeps the exact bytes it had before languages existed;
  `ChannelParser` defaults a missing value to English, which is what stops an app update landing
  before a pipeline run from emptying the Watch tab.
- `durations.json` — `{ "durations": { "<youtubeId>": seconds } }`.
- `play/climb.json` — the relic roster. Effects are code (`RelicEffect`); the roster is content,
  so a new relic ships as a commit rather than an app release. An entry naming an unknown effect
  is dropped by that install rather than fatal to it.
- `play/chains/YYYY-MM.json` — one grid per calendar day, 16 tiles, 4 groups of 4.

## Seeding

`ContentSeeder.seedIfNeeded()` prefers, in order: `assets/packs/` (CI-enriched) →
`assets/content/` (raw authoring) → `filesDir/packs/` (downloaded at runtime).

Two behaviours worth knowing:

- **Translated packs live one level down**, in `assets/packs/<tag>/`. The seeder scans those
  subfolders explicitly for every non-default language. Without that scan a translated pack ships
  inside the APK and is never installed — which is exactly what happened to Russian first time.
- **A pack is skipped when its `version` matches** what's installed. Version is a hash of the
  body, so re-seeding is free and content updates are automatic.

Whether installing replaces or merges is `PackInstall.replacesExistingFacts()` — curated packs
replace, `library-` shards merge. See `invariants.md`.

## Growing the library

The bundled 517 facts are a floor, not a ceiling. `LibraryTopUp` decides when to pull more:
when a category drops below 120 unseen facts, it downloads up to 3 more shards, neediest first.

That threshold was 15 and was a number chosen without doing the arithmetic — a fresh install
holds 70–95 unseen facts per category and the planner deals about two per category per day, so a
category would have taken 27–40 days to fall below 15. A mechanism nobody can see for a month is
indistinguishable from one that doesn't work.

Shards are downloaded, never bundled — which is how the app carries 3,501+ facts without the APK
growing.
