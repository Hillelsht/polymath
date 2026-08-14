<!-- covers: app/src/main/java/com/hillelsht/smart/domain/play/**, app/src/main/java/com/hillelsht/smart/ui/play/**, packs/play/**, tools/build_chains.py -->

# The Play tab

Five games. Four of them draw questions from the same fact corpus, and the decision that makes
those belong in this app rather than a separate one is: **a question you miss in a game is pushed
into your review queue.** Playing is another door into the spaced-repetition engine. Without that,
Play is a toy bolted to the side.

[The Vaults](#the-vaults) is the deliberate exception, for reasons in its own section.

Every game's rules live in `domain/play/` as pure Kotlin and are tested headlessly — most of the
306 engine tests are game rules. The UI draws with Compose vectors; there are no sprite sheets
and no game engine dependency, so none of this grows the APK.

`recordGameMiss()` has two deliberate refusals: it skips facts still in `Phase.NEW` (grading a
fact the learner was never taught would drop it into review having never been seen), and it does
not bump daily activity counters (a streak should not be sustainable by playing badly).

## The Climb

A roguelike tower. A run is 20–40 minutes; the meta-progression is months. Branching floors,
node types (duel, elite, cache, rest, boss every tenth), relics collected along the way, and
Insight banked on death to unlock relics for future runs.

**Fights are sized in questions, not hit points, and that is the whole trick.** The obvious
design — health rising with the floor — is quietly broken in a game with no ending: player damage
is capped by the streak multiplier, so linearly rising health means fights that get longer
forever. A floor-200 elite worked out at roughly 490 correct answers. Sizing in questions and
deriving health from that keeps every fight four to ten questions at any height; the tower gets
harder through what a mistake *costs*, and that cost is capped so one unlucky question cannot end
a good run.

The tower is a pure function of its seed, so a run is reproducible and testable. `ClimbTest` is
the largest single test class in the repo (29 tests) and asserts what balance means here: no
unendable fight, no immortality relic, no negative health, deterministic generation.

Relics are **content, not code** — the roster ships in `packs/play/climb.json`, the effects are
`RelicEffect` values. A new relic is a commit, not an app release. An entry naming an effect this
build doesn't know is dropped by that install rather than fatal to it.

## Chains

The daily hook: sixteen tiles concealing four groups of four, one grid a day, the same grid for
everyone.

Built in CI rather than on the device **because it must be identical for everyone**, and devices
hold different subsets of the library after top-up.

The defining failure of the format is the **overlap rule**: a tile that fits two groups gives the
puzzle no single solution. `build_chains.py` handles it twice over — a string is only eligible as
a tile if it is the answer to exactly one thing in the entire corpus, *and* every generated grid
is checked afterwards anyway, because a rule worth relying on is worth proving. `PlayContentTest`
then re-validates every published grid using the device's own `ChainsRules` before CI commits it,
and `SmartRepository` validates once more at load time.

The overlap rule is not the only way a grid can be wrong, which is what shipping the daily on the
web made obvious. **A group's label can lie about the tiles under it.** "Countries" went out
containing Xinjiang and the Maghreb; "Authors" went out containing Moses. Every one of those grids
passed every check above, because the overlap rule asks whether a tile fits *two* groups and has
nothing to say about a tile that fits its own group badly — and a solver who knows the Maghreb is
not a country is told they are wrong by a puzzle that is itself wrong, which is the same injury
the overlap rule exists to prevent. Fifty-nine tiles across four published months were like this.

`build_chains.py` now removes them with a rule that survives being applied to the whole corpus: a
real country is something this corpus writes facts *about*, and a region mislabelled as one is
not. That only holds where the corpus writes about the kind of thing at all — it has facts about
countries and athletes, none about currencies or chemical symbols, where being absent means
nothing — so the test runs per answerType and only where the type as a whole clears half. The
measured split is not close (countries 78%, athletes 100% against mountain ranges 15%, currencies
2%) and nothing sits between 32% and 61%.

It is deliberately biased toward removal. Tolkien and Lewis Carroll go with Moses, because this
corpus holds no fact about either man, and **Authors and Musicians drop below the pool floor and
stop appearing at all**. Eleven good authors beat eighteen with Moses among them; the way to get
the category back is more content, not a lower bar.

A second rule drops facts whose subject sits far below the corpus median importance, where one is
stated: "What is the chemical symbol for unquadoctium?" scored 8 against tantalum's 146, because
unquadoctium is a hypothetical element nobody has made and its symbol is a naming convention
rather than a fact. Hand-authored facts state no importance and are exempt.

Four mistakes allowed. Score is `1000 - 150 × mistakes` on a win, or 100 per solved group on a
loss.

Played on the web at `chains.html`, driven end-to-end in Chromium by `tools/playtest/daily.js`.
That page is why `ChainsState` carries `guesses` — an ordered list of what was submitted, with
each guess's tiles in tap order. `attempts` cannot stand in for it, being a set of sets whose only
job is "have I seen this combination before", and a shared result grid is one row per guess. The
same list is the saved game: replaying it through `ChainsRules` rebuilds a part-finished grid
exactly, so nothing derived is stored and nothing derived can fall out of step.

## Gambit

Chess against an engine that gets weaker as you answer questions. Between moves you bank *tempo*,
which buys a hint, a takeback, or drops the engine's search depth a level. Knowledge makes the
opponent weaker without touching the chess.

The chess itself is a full implementation: 0x88 board, packed-int moves, make/unmake, negamax
with alpha-beta, iterative deepening, quiescence search, MVV-LVA ordering, killer moves.

It is here second because it is **the most rigorously verifiable thing in the app**. `perft` —
counting leaf nodes to depth N from published positions — proves move generation exactly against
known-correct numbers, not by sampling. `PerftTest` runs the standard six-position suite. That is
how a subtle bug was found: a rank is 16 on a 0x88 board, not 8, and writing 8 put the en passant
square off the board every time, so en passant was never generated at all — worth 258 missing
moves at five ply.

`EvaluationTest` asserts *comparative* properties (a centre knight beats a corner knight) rather
than transcribed piece-square numbers, which is why it caught a sign error that rewarded the king
for wandering toward the centre. `SearchTest` self-verifies every position against `isCheckmate`
and `legalMoves` — already proven by perft — before using it to grade the search.

Threefold repetition detection exists because of a self-play test: two engines searching one ply
deep have nothing better to do than repeat themselves, and without it a match between two weak
players never ends.

## The Vaults

A side-scrolling platformer in the *Prince of Persia* tradition, and the replacement for Palace.
**No questions during play**, which makes it the one game here that is not a door into the
scheduler. That is deliberate: it is the game you reach for when you do not want to be asked
anything. The intended link is an *economy* rather than a gate — studying would stock your pack
before you descend, with reviews becoming a flask and a streak keeping your checkpoint — and none
of that is built yet. Until it is, The Vaults earns its place by being worth playing.

It is original work. Prince of Persia itself cannot be shipped (it is Ubisoft's, and every open
port needs the original's copyrighted data files), so the level design and physics are this
project's own.

### What is built

`Motion.tick` is a pure `(runner, room, buttons) -> runner` step at a **fixed 60fps timestep**, so
a run is reproducible from its input sequence alone. That is what lets a test replay a room, and
what lets the browser harness and the phone agree frame for frame — verified bit-identical, and
re-verified per room every time `tools/playtest/play.js` runs.

Seven rooms, in a teaching order, under one clock:

| | teaches |
|---|---|
| the threshold | the jump — its gap is deliberately wider than a ledge-grab can bridge |
| step-down | height as a resource |
| loose stones | keep moving; stone that is stood on gives way |
| first blade | a cycle can be read |
| two beats | two cycles, out of step |
| the sill | a blade guarding the lip of a gap |
| the narrow | all of it, arranged so the lessons argue |

**The ledge-grab** is a safety net rather than a move to be timed: fall past a lip within reach and
you catch it, then press jump to climb. Its width is a *speed* decision, not a timing one — a body
running at full pace is already beyond the lip by the time it has fallen far enough to reach, so
the net catches short jumps and slow steps off an edge without quietly bridging every gap in the
game. `Playtest.walkOffGrabReach` computes exactly how far it reaches, and the first room asserts
its gap is wider than that.

**Blades** are a pure function of the frame counter, so a rhythm learned once stays learned. They
are tall enough that jumping over is not the answer. **Collapsing stone** breaks after
`collapseFrames` of being stood on and stays broken for the visit, but is restored on respawn — a
room is a puzzle to re-attempt, not a resource to exhaust by failing at it.

**The clock is the score.** `Descent` runs the rooms in order against one timer that keeps counting
while you are dead. Deaths are unlimited and free; that single decision is what made *Prince of
Persia* something people replayed. Dying is not punished — dithering is.

Combat and further acts come after this is proven good, which is the sequencing Palace never got.

Three things are in from the first commit, because a platformer without them feels like it is
ignoring you no matter how the levels are designed:

| | |
|---|---|
| **Input buffering** | a press is held for `jumpBufferFrames` and cleared only when spent or expired — never silently dropped |
| **Coyote time** | `coyoteFrames` of grace to still jump after walking off an edge |
| **Momentum** | acceleration and friction rather than snapping between 0 and full speed, so a run is a commitment and a careful step is possible |

`Tuning` is a data class rather than a bag of constants, so a room, a test or the harness can run
the same physics at different settings; it will move to `packs/play/vaults/tuning.json` so a feel
fix ships as content. `Rooms` is Kotlin for now, on purpose — geometry is still moving while the
physics is being tuned, and there is no sense building a publishing pipeline around numbers that
change hourly.

### How it is verified

Two checks, deliberately covering different failure modes:

- **`MotionTest`** asserts that *imperfect* input works — a jump pressed 1 to 8 frames before
  landing still fires, a jump up to 6 frames after leaving an edge still fires, a stale press
  expires rather than firing late, a held button does not bounce.
- **`RoomsTest`** measures **timing margin**. `Playtest.solve` searches the plan space — wait this
  long, run, press jump at these moments — and `slack` then varies each timing on its own to find
  how far it could shift and still get through. The smallest such window is the room's margin,
  because a plan forgiving in every respect but one is only as playable as its tightest moment.
  Rooms currently run 14 to 81 frames; below `Playtest.MIN_MARGIN_FRAMES` fails the build.
- Every trap is asserted to be **able to kill**. A blade that catches nobody is scenery, and a
  margin measured against scenery certifies nothing.

Two honest limits, written down rather than assumed. Margin is **blind to input buffering**,
because it only presses jump from solid ground and so never meets the case buffering exists for —
that is `MotionTest`'s job. And `solve` *searches*; finding nothing means the search found nothing,
not that a room is impossible, so the gate fails closed. Neither number alone is proof of
playability, and treating one as proof is the mistake below.

### In the app

`ui/play/vaults/VaultsScreen.kt` is the Android front end, and it is deliberately thin: it draws
rectangles and forwards button state. Two details are load-bearing, both of them Palace's mistakes
inverted.

**A fixed timestep.** Palace advanced physics by the real frame delta, which quietly makes it a
different game on a 90Hz or 120Hz phone. The screen here accumulates elapsed time and spends it in
whole `Tuning.DT` steps, so a run is the same run everywhere — and the same one the tests and the
browser play. A gap longer than 200ms means the app was backgrounded and is discarded rather than
simulated, or the runner would teleport through a wall on resume.

**A jump latch.** A tap can begin and end between two frames, and a press no frame ever sees is a
press that did not happen — a second route to the silence that made Palace unplayable. The
ViewModel therefore reports jump as held for at least two frames after a press. It does *no* other
input bookkeeping: buffering belongs to `Motion`, which is where it can be tested.

Scoring is `DescentRules.score`: rooms cleared are worth a thousand each, and finishing inside par
adds a speed bonus on top. Depth dominates speed on purpose — `bestScore` keeps the maximum, and a
fast failure in room one must never outrank a run that nearly finished. Leaving partway down still
records the run.

### Playing it in a browser

`webplay/` compiles the same `domain/play/vaults` sources to JavaScript and renders them on a
canvas with live tuning sliders; `tools/playtest/play.js` drives it in Chromium. See
`webplay/README.md`. This is the round trip through a human that the Palace entry below said was
unavoidable — it was not.

Chains is compiled to JavaScript by the same build and published as **the daily** at
`chains.html`, driven end-to-end in Chromium by `tools/playtest/daily.js`. That page is the reason
`ChainsState` carries `guesses`: an ordered list of what was submitted, in the order the tiles were
tapped. `attempts` cannot stand in for it — it is a set of sets, because its only job is "have I
seen this combination before" — and the shared result grid is one row per guess, drawn straight off
`guesses`. The same list is the saved game: replaying it through `ChainsRules` rebuilds a
part-finished grid exactly, so nothing derived is stored and nothing derived can fall out of step.

## Aryeh's Palace — removed, and why it matters

Palace was a side-scrolling platformer. It shipped unplayable and was removed. It is worth
keeping the post-mortem, because the failure was one of *method*, not of effort.

The bug was a single line in `PalaceViewModel.tick()`. A tap set `pendingJump = true`; the next
tick read it and cleared it unconditionally, while the physics honoured a jump only
`if (input.jump && run.grounded)`. Any tap arriving on a frame where the player was airborne was
therefore **silently destroyed** rather than held until it could be used. Simulated against the
shipped constants, a player falling toward a ledge and tapping Jump to bounce off it:

| Tap timing | Result |
|---|---|
| 6 frames early (100 ms) | discarded |
| 3 frames early (50 ms) | discarded |
| 1 frame early (17 ms) | discarded |
| exactly on touchdown | discarded |
| 2 frames late | jumps |

Android touch latency is 50–150 ms, i.e. 3–9 frames. *Anticipating* a landing — the core skill in
any platformer — never worked. Only late taps did. Two further faults compounded it: no coyote
time, and no acceleration at all (`vx` snapped between 0 and ±260 px/s).

**Sixteen tests passed throughout.** They passed because they handed `tick()` `jump = true` on
exactly the frame the player was grounded — the ideal input a human cannot produce.
`PalaceLevelsTest` proved each level completable by *scripting the inputs that completed it*,
which establishes that a solution exists, not that anyone can execute it.

The lesson, and the standard any future action game here must meet:

- **A completability test must model input latency.** Re-run the solution at 0/50/100/150 ms. A
  level completable only at 0 ms is not completable by a person.
- **Report timing margin, not just success.** "This jump has 3 frames of tolerance" is a number
  CI can fail on. "The level is completable" is not.
- **Input buffering and coyote time are not polish.** An input is held until used or expired,
  never dropped. Ship them in the first commit or the game is unplayable regardless of level
  design.

None of that needed a device. Every measurement above came from a few lines of simulation against
the real constants. The capability existed the whole time and went unused; *that* was the failure,
not the missing hardware.

## Quiz

Ten multiple-choice questions, the original mode, now listed under Play while keeping its own
route so the entry points from Today, a category, and a finished video all still work.

`QuizGenerator` picks distractors in widening rings — same `answerType` first, then same
category, then the whole pool — comparing normalized text so a duplicate answer can never appear
twice. A question with no available distractors is dropped rather than shown short.
