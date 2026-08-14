# webplay — Polymath in a browser

This build exists because Aryeh's Palace shipped unplayable while its tests were green. The gap
was never coverage; it was that nothing ever *pressed a button*. `docs/games.md` recorded the
excuse at the time — feel "needs a real device, which is a round trip through a human rather than
through CI". This removes the round trip.

It has since grown from a Vaults harness into the portal itself: **Polymath**, the daily games
site published at <https://hillelsht.github.io/smart/>.

`webplay` compiles `app/src/main/java/com/hillelsht/smart/domain/play/vaults` and
`.../domain/play/chains` — the same Kotlin that ships in the APK — to JavaScript. There is no port
and no mirror, so there is nothing to drift.

## The site

| Page | What it is |
|---|---|
| `index.html` | The front door. Today's date, where you stand on today's grid, your streak. |
| `chains.html` | **The daily**: sixteen things, four hidden groups, four mistakes, one shareable result. |
| `vaults.html` | The descent, on a canvas, with the live tuning sliders this build was created for. |

Three pages, one JavaScript bundle and one stylesheet between them, so the second page a visitor
opens costs them nothing. `polymath.css` prefixes every custom property `--pm-`, because the Vaults
page carries its own palette under bare names and the two must not fight.

## Running it

```bash
gradle -p webplay bundle                     # -> webplay/build/web/
gradle -p webplay site                       # the same, without source maps or screenshots
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/play.js                # plays every Vaults room in Chromium, ~5s
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/daily.js               # plays the daily through its own buttons
python3 tools/playtest/inline.py             # fold The Vaults into one self-contained file
```

Open `webplay/build/web/index.html` in any browser to use it by hand. In The Vaults: arrows to run,
space to jump and to climb off a ledge, down to let go, `R` to restart. There are on-screen
controls too, so it works on a phone — worth doing, because touch latency is the thing that killed
Palace and it is easier to feel than to read about.

`?date=YYYY-MM-DD` opens any published day's grid, which is how the playtest drives a specific one.

## Where the day's grid comes from

The obvious design is to fetch this month's pack. It does not survive contact with how this project
is checked: `play.js` opens its page over `file://`, where fetching a sibling file is refused as a
cross-origin request. So the `dailies` Gradle task **bakes the grids into `dailies.js`** at build
time — a window starting one month back, so yesterday is always available and the file cannot grow
without bound. The daily then paints with no network at all.

The page still falls back to the published pack over the network for a month it was not built with,
and it has to: packs are refreshed by a bot, and **a bot push cannot trigger a workflow**, so the
site is not rebuilt when new grids land. Without the fallback a deploy left alone long enough would
simply run out of days.

Nothing else is stored anywhere but the visitor's own browser. A streak is derived from what is in
`localStorage`, and a part-played grid is rebuilt by **replaying its guesses through
`ChainsRules`** rather than by restoring a snapshot of the board — so no derived state is saved,
and none of it can rot.

## Why this works without an Android SDK

The same trick `enginetests/` uses: a standalone Gradle build whose `kotlin.srcDirs` points at the
app's real source directories. It compiles because `domain/play/vaults` and `domain/play/chains`
are **Kotlin stdlib only** — no `android.*` and, unlike the rest of `domain/`, no `java.*` either.
That constraint is what keeps this build possible; a single `java.time` import would end it.

`src/jsMain/kotlin/Bridge.kt` and `ChainsBridge.kt` are the only files in the project that know
JavaScript exists. Everything they expose is a number, a boolean or a string, so nothing about the
JS target leaks back into the games' own types. `ChainsSession` takes its grid one tile and one
group at a time rather than as parsed JSON, which means a malformed pack is caught by
`ChainsPuzzle.problems()` — the same check the content pipeline runs — instead of by a rendering
glitch halfway through someone's daily.

Kotlin/JS would normally download its own Node and yarn; the build points it at the system Node
instead, and stops before webpack, which is the part that needs npm. The compiler already emits
loadable UMD modules and the pages include them with plain `<script>` tags.

## What the harness is for

Two things testing alone cannot give you:

1. **Judging feel.** Drag a slider, feel the change immediately. The jump arc is drawn as a trail,
   because an arc is far easier to judge as a shape than as a sensation.
2. **Seeing the bug that sank Palace.** Set *buffer f* and *coyote f* to 0 and the controls
   immediately start ignoring you — that is what shipped.

The margin readout is live and turns red below the floor in `Playtest.MIN_MARGIN_FRAMES`, so a
tuning change that makes the game feel snappier while quietly halving the timing window is visible
at the moment you make it.

## The checks that matter

`node tools/playtest/play.js` re-measures every room's margin **in the browser** and plays every
room through the page's own controls. The browser figures match the JVM's exactly, room for room:

```
  threshold      margin 14f  cleared in  2.5s  (wait 0, jump +45)
  step-down      margin 81f  cleared in  2.6s  (wait 0, jump +73)
  loose-stones   margin 81f  cleared in  2.6s  (wait 0, jump +0)
  first-blade    margin 31f  cleared in  2.8s  (wait 13, jump +0)
  two-beats      margin 38f  cleared in  3.7s  (wait 47, jump +0)
  the-sill       margin 37f  cleared in  3.7s  (wait 61, jump +55)
  the-narrow     margin 38f  cleared in  3.5s  (wait 40, jump +77)
```

Two numbers agreeing across two compilers is what proves this is one implementation rather than
two that have drifted.

`node tools/playtest/daily.js` applies the same lesson one layer up. `ChainsRules` is thoroughly
tested and every published grid is validated by `enginetests`, and none of that would notice a
Submit button that stays disabled, a result panel that shows before there is a result, a board that
forgets itself on refresh, or a share grid with its rows in the wrong order. Those are the things a
daily actually *is*, so they get clicked. It runs over `http://127.0.0.1` from `serve.js` rather
than `file://`, because Chromium refuses `localStorage` on a file origin and a streak that cannot
survive a reload is not a streak.

Note that the margin metric responds to geometry and coyote time but **not** to input buffering —
it only presses jump from solid ground, so it never exercises the case buffering exists for. That
case is covered by `MotionTest`. Neither check alone is proof of playability, which is the whole
lesson of the game this replaces.

## Publishing

`.github/workflows/web.yml` runs the engine tests, both playtests and the standalone build, then
deploys `webplay/build/site` to GitHub Pages on every merge to `main` that touches a game, a pack
or this directory. A pull request runs everything and deploys nothing, so it can prove the games
still play without replacing what is live.

`tools/playtest/inline.py` produces a single self-contained HTML file from any of these pages,
defaulting to The Vaults. It is the only form an Artifact can take, since a strict CSP there blocks
every external request — and it is deliberately not the daily, which would arrive frozen on
whatever day it was built.
