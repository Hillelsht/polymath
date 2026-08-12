<!--
  This file intentionally declares no `covers:` line. Every doc in docs/ has one, which the
  pre-push hook (.claude/hooks/docs_freshness.py) uses to decide which doc to ask about when
  you change a file. This file summarises all of them, so giving it coverage would make it fire
  on nearly every change and train the reader to dismiss the check. Update it when you update
  the doc it is summarising.
-->

# Smart

Android trivia-teaching app: Kotlin, Compose, Material 3, Room, MVVM. Spaced repetition (SM-2)
over a curriculum of 4,000+ facts, plus a Watch tab of allowlisted YouTube channels and a Play
tab of four games. English, Russian and Hebrew.

Detailed references live in `docs/` — see the map at the bottom. Read this file first.

---

## This environment cannot compile Android

There is no Android SDK here and no route to `dl.google.com`. **`./gradlew assembleDebug` cannot
run. CI is the only compiler.** Do not spend time trying to make a local Android build work.

What this means in practice: a Kotlin change is unverified until CI says otherwise, so use the
local checks below to catch what they can, and expect the compiler's real verdict to arrive
minutes later in a workflow log.

## Commands that do work

```bash
gradle -p enginetests test                       # 292 tests, seconds, no SDK needed
gradle -p tools/parsecheck compileKotlin 2>&1 | tee /tmp/parse.log
python3 tools/compile_scan.py /tmp/parse.log     # filters ~3,200 expected errors, shows the rest
python3 tools/import_audit.py                    # forgotten imports
python3 tools/generate_facts.py --self-test      # offline, as CI runs it
```

**`gradle -p enginetests test` is mandatory after touching `domain/` or `data/seed/`.** That
build compiles those two directories *verbatim* — it tests the exact source that ships, so a
change there is a change to tested code. It is also the only genuinely authoritative local check.

The parse check catches syntax errors, type mismatches and bad overload resolution. It cannot
catch Compose or Room errors. Its current baseline is ~970 unexplained lines, essentially all
androidx symbols — compare against that number, don't expect zero.

## Git

**Always fetch and rebase before pushing.** Four CI workflows commit to `main` on their own
schedules, the durations job hourly. A push composed against a stale tip gets rejected.

```bash
git fetch origin main && git rebase origin/main
```

Commit messages: imperative, sentence case, no type prefix, no trailing period, and they explain
the *consequence* rather than the diff — "Actually install the Russian pack, which shipped
nowhere". Bot commits are `[tag] Fixed sentence` and the workflows' retrigger guards match on
those tags, so that format is functional.

Verify a release by its **artifact** — asset id, upload timestamp, sha256 — not by a workflow's
green conclusion. A green build with a stale APK looks identical to a successful ship.

## Invariants worth knowing before you touch anything

Full list with the story behind each: `docs/invariants.md`.

- **Translated packs need language-suffixed `packId` *and* fact ids** (`geography-he`,
  `geo-001-he`). Reusing English ids overwrites English facts and destroys review history.
- **A pack in `packs/` also needs a copy in `app/src/main/assets/packs/`** (under `<tag>/` if
  translated), or it ships nowhere.
- **Hebrew has two identical string files** — `values-he/` and `values-iw/`. Edit both.
- **Room migrations are always additive.** Review history is the only data that cannot be
  re-downloaded.
- **`domain/` and `data/seed/` must not import anything Android** — that is what keeps
  `enginetests` compiling.
- **Bot-authored pushes cannot trigger workflows.** A pipeline commit will never start a Build
  run on its own; dispatch it manually.
- **Assert invariants in tests, not snapshots.** A test encoding today's data is a landmine.

## Layout

```
app/src/main/java/com/hillelsht/smart/
  domain/   pure Kotlin, no Android imports — learning engine + game rules
  data/     Room, remote services, seeder, the single repository
  ui/       Compose screens, one package each
enginetests/  separate JVM build; compiles app/domain + app/data/seed directly
tools/        Python content pipelines, all stdlib-only
packs/        generated content, served to installed apps from raw.githubusercontent.com
```

## Where to look next

| Question | File |
|---|---|
| How is the code organised? Why is `domain/` pure? | `docs/architecture.md` |
| Room schema, migrations, JSON pack shapes, seeding | `docs/data-model.md` |
| The Python tools, the six workflows, what commits to `main` | `docs/content-pipeline.md` |
| Adding a language; why Hebrew has two string files | `docs/localization.md` |
| The four games and their rules | `docs/games.md` |
| Building, verifying and shipping without a compiler | `docs/development.md` |
| Things that must stay true, and what broke when they didn't | `docs/invariants.md` |
