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
  node tools/playtest/play.js --room 0       # plays it in Chromium, writes a screenshot
```

Open `webplay/build/web/index.html` in any browser to play it by hand:
arrow keys to run, space to jump, `R` to reset, `1`/`2` to switch rooms.

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

Measured on the current rooms:

| tuning | margin |
|---|---|
| buffer 8, coyote 6 (shipping) | 19 frames (317 ms) |
| coyote 0 | 13 frames (217 ms) |

Note that the margin metric responds to coyote time but **not** to input buffering — it only
presses jump while grounded, so it never exercises the case buffering exists for. That case is
covered by `MotionTest`. Neither check alone is proof of playability.
