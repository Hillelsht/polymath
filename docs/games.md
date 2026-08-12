<!-- covers: app/src/main/java/com/hillelsht/smart/domain/play/**, app/src/main/java/com/hillelsht/smart/ui/play/**, packs/play/**, tools/build_chains.py -->

# The Play tab

Five games, all drawing questions from the same fact corpus. The decision that makes them belong
in this app rather than a separate one: **a question you miss in a game is pushed into your
review queue.** Playing is another door into the spaced-repetition engine. Without that, Play is
a toy bolted to the side.

Every game's rules live in `domain/play/` as pure Kotlin and are tested headlessly — 190+ of the
292 engine tests are game rules. The UI draws with Compose vectors; there are no sprite sheets
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

Four mistakes allowed. Score is `1000 - 150 × mistakes` on a win, or 100 per solved group on a
loss.

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
