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

**A pack that ships is still not a pack anybody can get.** It must also be listed in
`manifest.json` for its language — `packs/manifest.json`, or `packs/<tag>/manifest.json`.
→ `PackService` reads exactly two files to discover content: the manifest, and
`library/index.json`. A pack in neither is bytes on a CDN nothing will ever request. Two things
were wrong at once when this was written. `packs/ru/manifest.json` and its Hebrew twin had never
existed, so `fetchManifest` 404'd for both languages and returned `null` — which is *also* what
"CI has not published yet" looks like, so an empty Library tab was indistinguishable from a
working one. And the manifest was rebuilt from `assets/content/*.json` alone, which made a topic
pack in `packs/community/` unpublishable rather than merely unpublished: adding it by hand worked
until the next content run silently deleted it. `tools/build_manifest.py` now writes every
catalogue from what is actually published; `build.yml` runs `--check`; `CatalogueTest` fails the
build if a published pack is unlisted or a listed one is missing.

**A pack's folder and its declared language must agree.** English at `packs/community/`, every
other language at `packs/<tag>/community/`.
→ `build_manifest.py` reads the declared `language`, not the path, so a Russian pack sitting under
`packs/community/` is correctly declined by English — and by Russian, which never looks there. It
is published, served, and unreachable. The first multilingual topic run did exactly this: 81
Russian and 34 Hebrew facts, translated perfectly, written to the English folder, skipped by every
catalogue, **and the run reported success**. Skipping was right; saying nothing was not.
`build_manifest.py --check` now fails on any fact pack no catalogue claims.

**A published pack must name its own `version`.** Not left out for `ContentParser` to fall back on.
→ The fallback is `"text-${raw.hashCode()}"`, which no tool outside the app can reproduce — so the
catalogue advertises one version and the device computes another, forever. The device concludes it
is out of date on every single refresh and re-downloads a pack it already has, for as long as the
app is installed. Both translated packs shipped that way. `build_manifest.py` stamps one and
mirrors it into the bundled twin; `CatalogueTest` compares the two strings the device compares.

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

**A language change reaches whatever reads `currentLanguage()` at call time, and nothing else.**
`SmartRepository` is built once per process and Settings only calls `Activity.recreate()`, which
retains the `ViewModelStore`. UI strings switch at once, and so does the Chains daily, because
`chainsPuzzle` consults the preference on every call. `facts` and `factsIn` build their query once
and do not: they change on the next launch.
→ That was a documented limitation while nothing but English shipped and is a live bug now that
the Russian and Hebrew libraries do. Fixing it means making the choice observable, not moving the
read — re-collecting a flow is not enough when the ViewModel holding the subscription survives.

**Chains is published, cached and recorded per language; English is the unsuffixed path.** The
grid is `chains/<month>.json` in English and `chains/<tag>/<month>.json` elsewhere; the cached
month and the `game_daily` row are namespaced to match (`chains` vs `chains:<tag>`).
→ They are different puzzles built from different libraries, not one puzzle translated. A shared
result row would report today as already played the moment someone switched language, then reopen
the new grid wearing the old one's solved groups — group ids like `sport` recur across languages,
so half of it would have looked right. Keeping English unsuffixed everywhere is what stops any of
this moving a streak that already exists.

**Every language must publish a grid on every day English does.** `PlayContentTest` asserts it.
→ The app asks its own language's folder and shows "no puzzle today" when the file is absent, so a
month curated in English and skipped in Hebrew is a blank Play tab for Hebrew readers only —
invisible to every other check, since each language passes everything else on the days it does
publish.

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

**A green test suite is not proof a game is playable.** Aryeh's Palace had sixteen passing tests
and could not be played at all. They passed by handing the physics `jump = true` on exactly the
frame the player was grounded — the perfect input a human cannot produce — while the shipped
ViewModel destroyed any press that arrived mid-air.
→ For anything with real-time input, assert that *imperfect* input works: presses that arrive
early, presses that arrive late. And measure the **timing margin** — the width of the window of
inputs that succeed — because "a solution exists" and "a person can execute one" are different
claims. `Playtest.margin` is the number; `docs/games.md` has the post-mortem.

**`domain/play/vaults/` and `domain/play/chains/` are Kotlin stdlib only — no `java.*`, not just
no `android.*`.**
→ That is what lets `webplay/` compile the same source to JavaScript, so the games can be played
and tuned in a browser instead of only on a device — and, since the portal went up, so the daily
on the web and the daily in the app are one implementation rather than two. A single `java.time`
import ends it. The rest of `domain/` is held only to the weaker no-Android rule.

**Never create a new repository called `smart`.**
→ The repo was renamed `smart` → `polymath`. GitHub keeps serving the old name, so
`raw.githubusercontent.com/Hillelsht/smart/...` still resolves and every app installed before the
rename still receives content. That alias dies the instant a new repository claims the name, and
the failure is silent: old installs simply stop getting packs, with no error anyone would see. The
same applies to the Pages path — links people have already shared point at `/smart/`.

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
