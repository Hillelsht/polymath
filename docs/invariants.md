<!-- covers: app/src/main/java/com/hillelsht/smart/data/local/Entities.kt, app/src/main/java/com/hillelsht/smart/data/local/ContentSeeder.kt, app/src/main/java/com/hillelsht/smart/domain/PackInstall.kt -->

# Invariants

Things that must stay true. Every one of these is here because breaking it produced a real bug,
usually a silent one. Scan this before changing content handling, the schema, or anything
language-related.

The rule is stated first, then what happened when it wasn't followed.

---

## Content and packs

**A translated pack must carry a language-suffixed `packId` *and* language-suffixed fact ids.**
`geography-he`, `geo-001-he` — never `geography`, `geo-001`.
→ The seeder keys packs by `packId` alone and `clearPack(packId)` deletes by it. A translated
pack reusing the English id overwrites the English facts *and* orphans their review history,
which is the one thing in the database that cannot be rebuilt from the network. Enforced by
`RussianPackTest` and `HebrewPackTest`, which assert no id overlap with the English pack.

**A pack committed to `packs/` is not a pack that ships.** It must also be copied into
`app/src/main/assets/packs/`, under `<language-tag>/` if it is translated.
→ `packs/ru/geography.json` was published and bundled nowhere. A Russian speaker got an empty
Read tab and an empty daily plan while the facts sat in the repository. Two things had to be
true and only one was: `ContentSeeder.readBundledPacks()` also had to learn to scan
`assets/packs/<tag>/` subfolders, because it only listed top-level files.

**Generated library shards merge; curated packs replace.** `PackInstall.replacesExistingFacts()`
decides, keyed on the `library-` prefix.
→ A regeneration returned 4,160 facts one month and 3,501 the next, because one SPARQL template
timed out. Clearing on that basis would delete facts the learner had studied and leave their
review schedule pointing at rows that no longer exist.

**`answerType` stays in English in every language.** The Hebrew pack translates `title`,
`statement`, `question`, `answer`, `hook` and `details`, and leaves `answerType: "capital"`.
→ It is the key that groups distractors. Translating it would silently split each type per
language and starve the quiz generator of same-typed wrong answers.

---

## Language

**`Language.tag` is a wire format.** It is the SharedPreferences value, the resource qualifier,
and the pack path segment. Renaming one orphans every saved preference and every published path.

**Hebrew needs both `values-he/` and `values-iw/`, identical.**
→ Android's resource matching never fully settled on `he` over the deprecated `iw`. RTL layout
and locale logic resolve `he` correctly because they go through a different, string-tag check —
so on device, the layout mirrored and JSON content displayed in Hebrew while *every* UI string
silently fell back to English. Editing one file without the other reintroduces exactly that.

**Any field carrying a language must have no Kotlin default.** See `ChannelEntity.language`.
→ It had one. Two places construct that entity, written weeks apart, and only one of them knew
about languages — the other quietly took the default and stored all 53 channels as English,
which made the Russian shelf fall back to the English one. The fix was to delete the default so
the compiler demands an answer at every construction site, and to funnel both through the single
`WatchChannel.toEntity()` mapper. The Room `defaultValue` is a different thing and is fine: it
backfills existing rows during migration.

**The language filter does not fall back.** If no channel matches the selected language,
`refreshShelf()` publishes an empty shelf.
→ Falling back to the full list sounds forgiving and is not: it turns "your language has no
channels" into a shelf of videos you cannot follow, and hides the fault instead of showing it.

**Content language changes take effect on next launch, not immediately.** `SmartRepository` is
built once per process and Settings only calls `Activity.recreate()`. UI strings switch at once;
facts and videos do not. This is a known limitation, documented at the `currentLanguage`
parameter — not a bug to re-fix.

---

## Database

**Every migration is additive.** Add a column with a default, add an index, create a table.
Never drop, never rewrite.
→ Review history is the only thing in this database that cannot be rebuilt from the network.
Everything else — facts, videos, channels, durations, relics, grids — re-downloads.

**A new column needs its `defaultValue` in *both* the migration SQL and the `@ColumnInfo`.**
SQLite demands one when adding a NOT NULL column to an existing table, and Room compares the two
schemas on open and fails if they disagree.

---

## Tests

**Assert invariants, not snapshots.** A test that encodes today's data is a landmine with a
timer on it.
→ `ChannelLanguageTest` once asserted that every published channel was English. True the hour it
was written; false the hour the pipeline first published the Russian ones. It turned a correct
file into a red build. It now asserts what must always hold — every language keeps at least one
channel, every channel has a UC id — and says so in a comment.

**`gradle -p enginetests test` is mandatory after touching `domain/` or `data/seed/`.** Those
directories are compiled verbatim by the `enginetests` build; a change there is a change to the
tested code, not near it.

---

## Pipelines

**An id from a search result is a guess until a probe confirms it.**
→ A hand-entered YouTube channel id for `@pushkinmuseum` pointed at a different institution
entirely. It was caught only because CI's prober independently resolves handles and the log was
cross-checked against the hand-entered values. A wrong pinned fallback is worse than none: it
resolves successfully and serves the wrong channel forever.

**Bot-authored pushes cannot trigger other workflows.** The content pipeline commits with the
default `GITHUB_TOKEN`, and GitHub deliberately blocks token-authored pushes from starting new
workflow runs — an anti-recursion safeguard.
→ A Build run for a pipeline commit will never appear on its own. It needs a manual
`workflow_dispatch`. This looks exactly like CI being slow, and is not.

**Always `git fetch` and rebase before pushing.** Four workflows commit to `main` on their own
schedules, the hourly one most often. A push composed against a stale tip is rejected, and a
pipeline run that loses the race throws away several minutes of probing.
