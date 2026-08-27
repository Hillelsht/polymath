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
tab of five games. English, Russian and Hebrew.

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
gradle -p enginetests test                       # 339 tests, seconds, no SDK needed
gradle -p enginetests publishRooms -Pmonths=4    # curate the daily Vaults rooms, ~1 min/month
gradle -p tools/parsecheck compileKotlin 2>&1 | tee /tmp/parse.log
python3 tools/compile_scan.py /tmp/parse.log     # filters ~3,200 expected errors, shows the rest
python3 tools/import_audit.py                    # forgotten imports
python3 tools/generate_facts.py --self-test      # offline, as CI runs it
python3 tools/validate_pack.py packs             # the contract every pack must meet
python3 tools/build_manifest.py --check          # is every published pack actually reachable?
python3 tools/topic_llm.py --self-test           # the model's gates, against a fake model
python3 tools/topic_pack.py --topic "space"      # Wedge 3's front door, deterministic
python3 tools/topic_pack.py --topic "x" --llm    # ...and with the model (needs a key, so CI)

gradle -p webplay bundle                         # the games -> JavaScript, in build/web/
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/play.js                    # plays every Vaults room in Chromium, ~5s
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/daily.js                   # plays the daily grid in Chromium, ~10s
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/ghost.js                   # races a shared ghost link, ~10s
python3 tools/playtest/inline.py --self-test     # the one-file build, offline
```

**The games can be played here.** `webplay/` compiles `domain/play/vaults` and
`domain/play/chains` — the same source the APK ships — to JS and serves them as **Polymath**, the
daily portal published at <https://hillelsht.github.io/polymath/>. Feel is testable in seconds rather
than being a round trip through a human, which is what Aryeh's Palace died of. See
`webplay/README.md`.

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
- **A translated pack lives under `packs/<tag>/`, matching its declared `language`** — folder and
  field must agree, or every catalogue declines it and it ships nowhere.
- **And it needs a row in `manifest.json` for its language**, or nothing can download it —
  `PackService` discovers content through the manifest and `library/index.json`, and nowhere else.
  Run `tools/build_manifest.py`. It also stamps a `version` into any pack lacking one, without
  which the catalogue and the device disagree forever and the pack re-downloads on every refresh.
- **Hebrew has two identical string files** — `values-he/` and `values-iw/`. Edit both.
- **Room migrations are always additive.** Review history is the only data that cannot be
  re-downloaded.
- **`domain/` and `data/seed/` must not import anything Android** — that is what keeps
  `enginetests` compiling.
- **`domain/play/vaults/` and `domain/play/chains/` must not import `java.*` either** — stdlib
  only. That is what keeps `webplay` compiling to JavaScript, and one `java.time` import would
  end it.
- **Never create a new repository called `smart`.** The repo was renamed to `polymath`; GitHub
  still serves the old name, which is what keeps pre-rename installs receiving content. A new repo
  with that name kills the alias silently.
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
| Authoring a pack from outside; the contract, the validator, the topic front door | `docs/community-packs.md` |
| The five games and their rules | `docs/games.md` |
| The web portal, the daily, playing the games in a browser | `webplay/README.md` |
| Building, verifying and shipping without a compiler | `docs/development.md` |
| The startup plan, current status, and what to build next | `plan.md` |
| Things that must stay true, and what broke when they didn't | `docs/invariants.md` |
