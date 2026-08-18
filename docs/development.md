<!-- covers: build.gradle.kts, settings.gradle.kts, gradle.properties, app/build.gradle.kts, gradle/libs.versions.toml, enginetests/**, webplay/**, tools/playtest/**, tools/parsecheck/**, tools/compile_scan.py, tools/import_audit.py -->

# Working in this repository

## The one fact that changes everything

**This development sandbox cannot compile Android.** There is no Android SDK and no route to
`dl.google.com`, so `./gradlew assembleDebug` cannot run here and never will. **CI is the only
compiler.**

Everything below follows from that. A change is not "probably fine" because it looks right — it
is unverified until either the JVM test suite or a CI run says otherwise, and the round trip to
CI is minutes. The tooling exists to shorten that loop, not to replace it.

`build.yml` reprints the Kotlin `e:` lines at the end of a failed build for the same reason: the
CI log is the only place compiler errors are legible, and burying them under 130 lines of Gradle
internals makes a red build unreadable.

## What runs locally

### The engine tests — the real safety net

```bash
gradle -p enginetests test
```

331 tests, no Android SDK needed, seconds to run. `enginetests` is a **separate Gradle build**
(its own `settings.gradle.kts`) that points `kotlin.srcDirs` straight at the app's
`domain/` and `data/seed/` directories. It compiles and tests **the exact source that ships** —
not a copy, not a port.

Two consequences worth internalising:

- Changing anything under `domain/` or `data/seed/` changes tested code. Run this. It is the one
  local check that is genuinely authoritative.
- Those directories must stay free of Android imports, or this build stops compiling. That is
  the constraint that keeps the learning engine testable, and it is load-bearing rather than
  stylistic. See `architecture.md`.

Test resources point at `app/src/main/assets`, so the content tests parse the real shipped
curriculum. Generated content under `packs/` is read through relative `File("../packs/...")`
paths instead, because it lives outside assets.

This build also **writes** one piece of content, which no other test build does:

```bash
gradle -p enginetests publishRooms -Pmonths=4        # -> packs/play/vaults/
```

Curating a daily Vaults room means running `Playtest.solve` over each candidate a few thousand
times, so it cannot live in `tools/` with the Python pipelines. It is a `JavaExec` over the test
classpath rather than a test, because a test that edits the repository is a test you cannot run
twice with confidence — `DailyRoomsTest` is the half that belongs in the suite, and it re-measures
every day the task published. See `docs/games.md`.

### The browser build — the only thing here that can be played

```bash
gradle -p webplay bundle                     # -> webplay/build/web/  (portal + games)
gradle -p webplay site                       # the same, minus source maps and screenshots
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/play.js                # plays all seven Vaults rooms in Chromium, ~5s
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/daily.js               # plays the daily grid through its own buttons
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/ghost.js               # races a shared run, end to end
python3 tools/playtest/inline.py             # fold The Vaults into one self-contained file
```

`webplay/` uses the same trick as `enginetests` — a standalone build pointing `kotlin.srcDirs` at
`domain/play/vaults` and `domain/play/chains` — but targets **JavaScript**, so both games run
here. Chromium and Playwright are already installed; nothing is downloaded, and the Kotlin/JS
toolchain is pointed at the system Node and stopped before webpack so npm is never involved.

It builds four pages sharing one bundle and one stylesheet: `index.html` (Polymath, the front
door), `chains.html` (the daily grid), `descent.html` (the daily room, with ghost racing) and
`vaults.html` (the tuning harness). The room is drawn by `vaults-draw.js`, shared by the last two.
The day's content is baked in by the `dailies` task rather than fetched, which is what lets the
pages work offline and over `file://` — the grids into `dailies.js` and the rooms into `rooms.js`,
one file per game so the descent does not load a few hundred kilobytes of Wikidata labels it has no
use for. Either page still falls back to the published pack for a month it was not built with,
because a pack refreshed by a bot cannot trigger a rebuild of this site.

This exists because the previous game shipped unplayable past a full headless suite: no test had
ever pressed a button. `play.js` presses them, and re-measures every room's timing margin in the
browser — those figures matching the JVM's is what proves the two targets have not drifted.
`daily.js` does the same job one layer up, over http rather than `file://` because a daily is made
of things a `file://` origin does not have: `localStorage`, and therefore a streak that survives a
reload. `ghost.js` adds the check the generated rooms depend on — it clears today's room by
following the plan the margin solver published for it, so a browser that can no longer execute the
JVM's own answer fails the build. The extra constraint all this buys is in `invariants.md`: `domain/play/vaults` and
`domain/play/chains` are **stdlib only**, no `java.*` either, or this build stops compiling.

Full detail in `webplay/README.md`.

### The parse check — a compiler stand-in

```bash
gradle -p tools/parsecheck compileKotlin 2>&1 | tee /tmp/parse.log
python3 tools/compile_scan.py /tmp/parse.log
```

`tools/parsecheck/` is a third standalone build that compiles the app's **full** source tree
with only the Kotlin stdlib, coroutines and serialization on the classpath. Everything
androidx-shaped fails to resolve — around 3,200 errors, all expected. `compile_scan.py` buckets
those away and prints only what it cannot explain, never truncating (it was written after a real
`Assignment type mismatch` sat unnoticed in a log while CI went red twice, because a hand-rolled
`grep | head -30` cut it off).

**What it catches:** syntax errors, type mismatches, bad overload resolution, and anything wrong
in the pure-Kotlin domain layer.
**What it does not catch:** Compose compiler errors, Room annotation processing, and anything
needing a resolved androidx symbol. Those still surface only in CI.

A useful current baseline: ~970 unexplained lines, essentially all androidx symbols the filter
doesn't know about yet. Compare against that number rather than expecting zero — a change that
adds ten is interesting, the standing 970 are not.

```bash
python3 tools/import_audit.py           # forgotten imports the parse check buries as noise
```

Heuristic, and has six standing false positives documented in its docstring.

### The Python pipelines

```bash
python3 tools/generate_facts.py --self-test    # no network needed
python3 tools/build_chains.py --self-test
```

Both have offline self-tests that CI runs before the real thing. `fetch_durations.py` self-tests
unconditionally on every run. See `content-pipeline.md`.

## Verifying a change, in order

Cheapest and most informative first:

1. `gradle -p enginetests test` — if you touched `domain/` or `data/seed/`, this is mandatory.
2. Parse check + `compile_scan.py` — if you touched Kotlin anywhere.
3. `python3 -c "import json; json.load(open(...))"` — if you touched a JSON pack. A malformed
   pack is caught by the seeder at runtime and logged, not crashed on, which means it fails
   *silently on device*.
4. `--self-test` — if you touched a generator.
5. `node tools/playtest/play.js`, then `daily.js` and `ghost.js` — if you touched
   `domain/play/`, `webplay/web/` or a pack. Seconds each, and the only checks here that answer
   "can this be played" and "does the link work" rather than "does this compute".
6. Push, then watch CI. This is the only step that compiles Android.

## Shipping

Every push to `main` triggers `build.yml`, which runs the engine tests, assembles the debug APK,
and republishes the rolling `latest` GitHub release. Installing is: open
`github.com/Hillelsht/polymath/releases/latest` on the phone, download `smart.apk`, tap.

**Verify a release by its artifact, not by the workflow's conclusion.** Check
`get_release_by_tag` for `latest` and confirm the asset's id, upload timestamp and sha256 digest
actually changed and postdate the build you care about. A green workflow with a stale APK looks
identical to a successful ship right up until someone installs it. This has been got wrong more
than once.

Note the trap in `invariants.md`: pipeline commits are authored by `GITHUB_TOKEN` and therefore
**cannot trigger Build at all**. If content CI just landed a commit and you need an APK from it,
dispatch Build manually.

## Git

Four workflows commit to `main` on their own schedules — the durations job hourly. So:

```bash
git fetch origin main && git rebase origin/main    # before every push, not occasionally
```

Commit messages here are imperative, sentence case, no type prefix, no trailing period, and they
explain the *consequence* rather than the diff — "Actually install the Russian pack, which
shipped nowhere", "Fix a channel test that asserted a snapshot, not an invariant". Bot commits
are strictly `[tag] Fixed sentence`, and the workflows' own retrigger guards match on those tags,
so that format is functional rather than cosmetic.

## Build configuration

`compileSdk` 35, `minSdk` 26, Java 17, Gradle 8.9, Kotlin 2.0.21. Versions are centralised in
`gradle/libs.versions.toml`. The debug build carries `applicationIdSuffix = ".debug"` so it
installs alongside a release build — and it is the debug APK that CI publishes.

The `youtubePlayer` version in the catalog carries a justification comment; it is pinned
deliberately to dodge a YouTube embed error. Read it before bumping.
