# The startup plan, and where we stand

*Written 2026-08-14. This is the handoff document: a new working session should read `CLAUDE.md`
first (build constraints, commands, invariants), then this file for strategy and current state.*

---

## Part 1 — Where we stand right now

### What shipped in the last working session (all merged to `main` via PR #1)

- **Aryeh's Palace was removed.** It shipped unplayable behind sixteen passing tests; the root
  cause (an unspent jump press cleared every frame) and the post-mortem live in `docs/games.md`.
  Orphaned `palace` rows in `game_runs` are deliberately left inert, not deleted.
- **The Vaults replaced it**: a Prince-of-Persia-style descent, seven rooms under one clock.
  Pure-Kotlin rules in `domain/play/vaults/` (stdlib-only — no `java.*`, which is what lets it
  compile to JavaScript). Input buffering, coyote time, momentum, ledge-grab, blades on cycles,
  collapsing stone, free deaths, the clock as the score.
- **Playability is now a number.** `Playtest.solve` measures each room's timing margin (how many
  frames the deciding input can shift and still get through); CI fails any room under 6 frames.
  Current rooms run 14–81 frames.
- **The browser build** (`webplay/`) compiles the same Kotlin to JS. `tools/playtest/play.js`
  plays every room in Chromium and re-measures margins — they match the JVM exactly, room for
  room, which proves the two targets are one implementation.
- **The Vaults is in the Android Play tab** (`GameId.VAULTS`, `ui/play/vaults/VaultsScreen.kt`),
  with fixed-timestep stepping and a jump latch — both of Palace's UI mistakes inverted.
- **Everything is live**: rolling APK at the `latest` release includes The Vaults; the browser
  build deploys to GitHub Pages at **https://hillelsht.github.io/smart/** via
  `.github/workflows/vaults.yml` on every merge to `main` that touches the game. Pages source is
  set to "GitHub Actions" in repo settings (done). 302 engine tests green.

### How to verify the current state (in a fresh environment)

```bash
gradle -p enginetests test                      # 302 tests, seconds
gradle -p webplay bundle                        # The Vaults -> JavaScript
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/play.js                   # plays all 7 rooms in Chromium, ~5s
```

Remember: **this environment cannot compile Android** — CI is the only compiler for `ui/` code.
Dispatch `build.yml` on a branch to verify Compose changes before merging.

---

## Part 2 — The startup concept (decided, not yet started)

### One line

**The daily games platform for knowledge: playable in your language, about anything, and it
actually teaches you.** Free dailies on the web install the habit; the app keeps the learning.

### Decisions already made (user-confirmed)

1. **First bet: daily web games** — the Wordle playbook: free, shareable, no install; the app is
   the retention layer, the web is the acquisition funnel.
2. **Zero-backend as long as possible** — static packs, share-by-link, streaks in localStorage.
   The cost structure (≈$0/month) is itself a moat: the company survives being small.
3. **AI topic packs as the core hook, gated** — "type a topic, get a daily" is the headline, but
   facts must trace to Wikidata claims; the LLM phrases, never invents.
4. **Rename now** — "Smart" is ungoogleable. Name + domain chosen before any links spread.
   **← this decision is still open: no name has been picked yet.**

### Why this can win — the empty intersection

|  | Ritual + share | Any language | Any topic | Actually teaches |
|---|---|---|---|---|
| NYT Games / LinkedIn games | yes | no (wordplay is English-locked) | no | no |
| Duolingo | yes | fixed verticals | no | chore-like |
| Anki / Quizlet | no | yes | DIY decks | no fun |
| **This** | yes | yes | yes | yes |

Every "Duolingo for general knowledge" died on content cost and lack of urgency. The generated
pipeline attacks the first; daily ritual + shareable results attack the second.

### Five unfair advantages already in this repo (verified)

1. **Content pipeline** (`tools/generate_facts.py`): Wikidata → facts, self-testing,
   CI-published. 4,018 EN facts today (517 bundled + 3,501 library).
2. **Deterministic engine**: a whole Vaults run IS its input list — a few hundred bytes. **Ghost
   races shareable as a bare URL, no server**: send a link, your friend races your ghost.
3. **The margin solver** (`Playtest.solve`) flips from QA tool to **content curator**: generate
   candidate rooms, publish only those whose margin lands in a target band. Provably-fair
   procedural dailies with an objective public difficulty number.
4. **Kotlin→JS**: verified — zero `java.*` imports in ALL of `domain/play/` (Chains, Climb, the
   Gambit chess engine included). Anything in the games layer can run in the browser.
5. **The learning loop** (`recordGameMiss` → SM-2 queue): "games that make you smarter" is
   literally true here, with receipts.

### Facts that shape the work (verified in-repo)

- Daily Chains grids already exist: `packs/play/chains/2026-08.json` … `2026-11.json`, 31/month,
  keyed by `date` string, with `tiles` + `groups` (id/label/members/difficulty).
- `GameDailyEntity` stores mistakes + solved order — enough for an emoji share grid.
- Share/deep-link scaffolding is greenfield: no `ACTION_SEND` anywhere, two intent-filters total.
- **The honest gap**: RU and HE have ~10 facts each vs 4,018 EN. Cross-language dailies are
  structurally ready (id convention `geo-001` / `geo-001-he`) but need a generation run first.

---

## Part 3 — The roadmap (next work, in order)

### Wedge 1 (~weeks 1–3): The Daily, on the web, shareable  ← START HERE

Grow `webplay/` from a Vaults harness into a small daily portal at a real domain:

1. **Pick the name + domain** (open decision — ask the user first).
2. **Daily Chains in the browser**: add `../app/src/main/java/com/hillelsht/smart/domain/play/chains`
   to the srcDirs in `webplay/build.gradle.kts` (same trick as vaults; verified JS-clean), fetch
   this month's grid JSON, play with `ChainsRules`, share as a Wordle-style emoji grid via
   clipboard.
3. **Daily Vaults room**: one counted attempt per day; share as a **ghost link** — the run's
   inputs base64-encoded in the URL fragment; opening replays the ghost to race against.
4. Streak + history in localStorage; result survives refresh.
5. Extend `.github/workflows/vaults.yml` into the portal deploy (Pages already proven live).
6. App: deep links `/daily/chains`, `/daily/vaults` into existing screens
   (`SmartNavHost.kt`, `AndroidManifest.xml`).
7. One privacy-friendly counter (Plausible-class) — the only metrics infrastructure.
8. **Quality pass on Chains grids** — valid ≠ fun; tile-taste heuristics in
   `tools/build_chains.py` before the daily is the flagship.

Ships when: page loads fast on a phone; emoji share pastes correctly; a ghost link round-trips;
streak survives restart; the Chromium playtest drives the daily end-to-end in CI.

### Wedge 2 (~weeks 3–6): Provably-fair generated rooms + language parity
- Room generator gated by `Playtest.solve` in CI (margin band → publish to
  `packs/play/vaults/daily/` like Chains grids). The day's margin is its public difficulty label.
- Run `generate_facts.py` to bring RU/HE toward parity; ship the language toggle — the same
  daily in three languages, the story NYT structurally cannot copy.

### Wedge 3 (~weeks 6–10): "About anything"
- Topic front door: topic → LLM maps to Wikidata subgraph → existing pipeline generates and
  validates the pack → games run on it. Facts must trace to Wikidata claims.
- Three vertical landing packs as SEO/community seeds (citizenship test, driving theory, one
  fandom). Community pack spec + validator so others can author packs by PR.

### Wedge 4 (~weeks 10–13): Decide with data
Only now revisit backend/accounts/monetization, gated on share-loop evidence from the counter.
Candidate model: dailies free forever; Plus (~$3–5/mo): custom packs, archive, ghost history,
family space, offline app.

### Deliberately NOT doing
No backend/accounts/realtime multiplayer before Wedge 4 data demands it. No AI chat tutor, no
ads in the ritual, no new games until the dailies are excellent.

---

## Part 4 — Instructions for the next session

1. Read `CLAUDE.md` (root) — build constraints, working commands, git rules, invariants map.
2. Read this file for strategy and state.
3. The immediate task is **Wedge 1**, starting with the name/domain decision (ask the user),
   then step 2 (Daily Chains in the browser).
4. Branch discipline: work on a feature branch, verify Compose changes by dispatching `build.yml`
   on the branch (this environment cannot compile Android), merge to `main` via PR — merges to
   `main` auto-publish the APK release and the Pages site.
5. Keep the existing gates green: `enginetests` (302 tests), `tools/playtest/play.js` margins,
   the docs-freshness pre-push hook (update `docs/` when code contradicts it — it will tell you).

Reference links: live game **https://hillelsht.github.io/smart/** · rolling APK
**https://github.com/Hillelsht/smart/releases/tag/latest** · post-mortem & game design
`docs/games.md` · browser build `webplay/README.md`.
