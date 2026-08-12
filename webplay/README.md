# webplay — playing the Vaults in a browser

This build exists because Aryeh's Palace shipped unplayable while its tests were green. The gap
was never coverage; it was that nothing ever *pressed a button*. `docs/games.md` recorded the
excuse at the time — feel "needs a real device, which is a round trip through a human rather than
through CI". This removes the round trip.

`webplay` compiles `app/src/main/java/com/hillelsht/smart/domain/play/vaults` — the same Kotlin
that ships in the APK — to JavaScript, and `web/index.html` renders it on a canvas with live
tuning sliders. There is no port and no mirror, so there is nothing to drift.

## Running it

```bash
gradle -p webplay bundle                     # -> webplay/build/web/
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/play.js                # plays every room in Chromium, ~5s
python3 tools/playtest/inline.py             # fold it into one self-contained file
```

Open `webplay/build/web/index.html` in any browser to play it by hand: arrows to run, space to
jump and to climb off a ledge, down to let go, `R` to restart. There are on-screen controls too,
so it works on a phone — which is worth doing, because touch latency is the thing that killed
Palace and it is easier to feel than to read about.

`tools/playtest/inline.py` produces a single self-contained HTML file from the same page. That is
what gets published to GitHub Pages by `.github/workflows/vaults.yml`, and it is the only form an
Artifact can take, since a strict CSP there blocks every external request.

## Why this works without an Android SDK

The same trick `enginetests/` uses: a standalone Gradle build whose `kotlin.srcDirs` points at the
app's real source directories. It compiles because `domain/play/vaults` is **Kotlin stdlib only**
— no `android.*` and, unlike the rest of `domain/`, no `java.*` either. That constraint is what
keeps this build possible; a single `java.time` import would end it.

`src/jsMain/kotlin/Bridge.kt` is the only file in the project that knows JavaScript exists.
Everything it exposes is a number, a boolean or a string, so nothing about the JS target leaks
back into the game's own types.

Kotlin/JS would normally download its own Node and yarn; the build points it at the system Node
instead, and stops before webpack, which is the part that needs npm. The compiler already emits
loadable UMD modules and the page includes them with plain `<script>` tags.

## What the harness is for

Two things testing alone cannot give you:

1. **Judging feel.** Drag a slider, feel the change immediately. The jump arc is drawn as a trail,
   because an arc is far easier to judge as a shape than as a sensation.
2. **Seeing the bug that sank Palace.** Set *buffer f* and *coyote f* to 0 and the controls
   immediately start ignoring you — that is what shipped.

The margin readout is live and turns red below the floor in `Playtest.MIN_MARGIN_FRAMES`, so a
tuning change that makes the game feel snappier while quietly halving the timing window is visible
at the moment you make it.

## The check that matters

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

Note that the margin metric responds to geometry and coyote time but **not** to input buffering —
it only presses jump from solid ground, so it never exercises the case buffering exists for. That
case is covered by `MotionTest`. Neither check alone is proof of playability, which is the whole
lesson of the game this replaces.
