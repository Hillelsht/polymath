# The startup plan, and where we stand

*Written 2026-08-14, updated the same day. This is the handoff document: a new working session
should read `CLAUDE.md` first (build constraints, commands, invariants), then this file for
strategy and current state.*

---

## Part 1 — Where we stand right now

### The name is decided: **Polymath**

The word is the pitch — someone who knows many things — and it survives transliteration into the
other two languages (Полимат / פולימת), which "Smart" and any English wordplay would not. The
rename is **the web brand only**, on purpose: page titles, share text, the repo and docs say
Polymath, while the Kotlin package stays `com.hillelsht.smart`. Renaming the
`applicationId` would make every existing install a different app and throw away its review
history, which is the one thing here that cannot be re-downloaded. That is a trade worth making
when there are users to keep, not before. **The domain is still unbought** — the share text points
at `hillelsht.github.io/polymath`, from a single constant in `webplay/web/polymath.js`.

**Deferred deliberately, not forgotten.** A domain waits until a share loop is visible: the site is
free on GitHub Pages, the ghost link already follows whatever host serves it, and the Chains share
reads one constant, so moving later is a ten-minute job. The one commitment that comes with waiting
is that **the GitHub Pages site must never be deleted** once links circulate — keep it as a
redirect, or every result anyone pasted into a chat becomes a dead end, and the share loop is the
whole growth mechanism.

### What shipped in this working session

- **The daily is live on the web.** `webplay/` grew from a one-game harness into the Polymath
  portal: a front door, `chains.html` (the daily grid) and `vaults.html` (the descent). Three
  pages, one JavaScript bundle and one stylesheet between them.
- **Chains compiles to JavaScript**, from the same `domain/play/chains` the APK ships — verified
  stdlib-clean, same trick as Vaults. `ChainsBridge.kt` hands the page numbers, booleans and
  strings and nothing else.
- **Shareable results.** `ChainsState` now carries `guesses`: what was submitted, in order, with
  each guess's tiles in tap order. That is what a Wordle-style emoji grid is made of, and
  `attempts` (a set of sets) structurally cannot answer either question. The same list is the
  saved game — replaying it through `ChainsRules` rebuilds a part-finished grid exactly, so no
  derived state is stored and none of it can rot.
- **Streaks, offline, no backend.** Grids are baked into the build (`dailies` Gradle task) so the
  page paints with no network and works over `file://`; it falls back to the published pack for a
  month it was not built with, which it must, because a bot's pack push cannot trigger a rebuild.
  Streak and history are `localStorage`.
- **The daily is gated in a real browser.** `tools/playtest/daily.js` plays it through its own
  buttons over http — sixteen tiles, a wrong guess costing exactly one life, a part-played grid
  surviving a reload, the share grid's rows matching the guesses, two days running reading as a
  streak of two. It caught a result panel that was showing before there was a result.
- **The Vaults has a daily too**, at `descent.html`, and it shares as a **ghost link**: the run's
  own inputs in the URL fragment, eight characters for a clean first room, replayed beside you
  frame for frame. No server, and none possible to need — the engine is deterministic, so the
  inputs *are* the run.
- **`vaults.yml` became `web.yml`**, building and publishing the whole portal.

### What shipped in the working session before this one (merged to `main` via PR #1)

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
  build deploys to GitHub Pages at **https://hillelsht.github.io/polymath/** via
  `.github/workflows/vaults.yml` (renamed `web.yml` since) on every merge to `main` that touches
  the game. Pages source is set to "GitHub Actions" in repo settings (done). 302 engine tests
  green.

### How to verify the current state (in a fresh environment)

```bash
gradle -p enginetests test                      # 306 tests, seconds
gradle -p webplay bundle                        # both games -> JavaScript
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/play.js                   # plays all 7 rooms in Chromium, ~5s
NODE_PATH=/opt/node22/lib/node_modules \
  node tools/playtest/daily.js                  # plays the daily through its own buttons
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
   **← the name is settled (Polymath, web brand only); the domain is not bought yet.**

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

### Wedge 1 (~weeks 1–3): The Daily, on the web, shareable  ← SHIPPED (bar the domain)

Grow `webplay/` from a Vaults harness into a small daily portal at a real domain:

1. ~~Pick the name~~ — **Polymath**, decided. **The domain is not bought yet**, and should be
   before any links spread; `polymath.js` holds the one constant to change.
2. ~~**Daily Chains in the browser**~~ — done. Compiled from the shipping source, played with
   `ChainsRules`, shared as a Wordle-style emoji grid via the clipboard or the system share sheet.
3. ~~**Daily Vaults room** + **ghost link**~~ — done, at `descent.html`. One room a day, chosen by
   day number so everyone gets the same one; your time is the first run you finish, and the room
   stays open to practise on afterwards. A finished run shares as a link carrying the run itself
   in the URL fragment — **a whole clearance of the first room is eight characters** — and opening
   it replays that attempt beside you, frame for frame. `Ghost` lives in `domain/play/vaults/`, so
   the format is tested by `enginetests` and compiled to JS from the same source; the round trip
   is gated end-to-end in Chromium by `tools/playtest/ghost.js`.
4. ~~Streak + history in localStorage; result survives refresh~~ — done, and gated in Chromium.
5. ~~Extend the Pages workflow into the portal deploy~~ — done; it is `web.yml` now.
6. ~~**App deep links**~~ — done. `Routes.CHAINS_LINKS` / `VAULTS_LINKS` claim
   `polymath://daily/chains|vaults` and the two `https://` daily pages, attached with
   `navDeepLink` and mirrored by manifest intent-filters. The custom scheme works on install; the
   https ones are declared with `autoVerify` and **stay inert until
   `.well-known/assetlinks.json` is published with the release signing fingerprint** — Android
   12+ ignores an unverified https filter outright. That file is the only step left, and it needs
   a release keystore rather than the debug one.
7. **A privacy-friendly counter** — the switch is built and **off**. `polymath.js` carries
   `COUNTER` and `count()`; filling in two strings loads a Plausible-class script, and until then
   the site makes no third-party request at all. This one cannot be finished here: it needs an
   account and a domain, both of which are yours to create. Turning it on also makes the "no
   server keeping any of this" line in two footers untrue, so change the words with it.
8. ~~**Quality pass on Chains grids**~~ — done. Two classes of wrongness the overlap rule could
   never see, both fixed and both documented in `docs/games.md`:
   - **A label lying about its tiles** — *Countries* containing "Xinjiang" and "Maghreb",
     *Authors* containing "Moses". 59 tiles across four published months, now 0. The rule: a real
     country is something the corpus writes facts *about*; applied per answerType and only where
     the type as a whole clears half, which is what stops it deleting every currency.
     **Authors and Musicians dropped below the pool floor and no longer appear** — eleven good
     authors beat eighteen with Moses among them, and the way to get them back is more content,
     not a lower bar.
   - **Questions about things that do not exist** — the app was asking "What is the chemical
     symbol for unquadoctium?", element 148, never made. 46 facts, fixed at source in
     `generate_facts.py` and stripped from the published shards so the daily is clean now.

Ships when: page loads fast on a phone; emoji share pastes correctly; a ghost link round-trips;
streak survives restart; the Chromium playtest drives the daily end-to-end in CI; and the grids
are worth showing a stranger. **Shipped.** What remains is not code: a domain, an assetlinks file
and a counter account.

### Wedge 2 (~weeks 3–6): Provably-fair generated rooms + language parity  ← SHIPPED

1. ~~**Room generator gated by `Playtest.solve`**~~ — done. `RoomGen` lays a room out from a seed
   using a grammar of the ideas the seven authored rooms teach; `curate` walks a seed stream and
   keeps only the candidates whose measured timing slack lands in the band the weekday asks for.
   122 days published to `packs/play/vaults/`, each carrying **a seed, its margin and the plan that
   achieved it** — never geometry, which would be a second copy of the room that could disagree
   with the first. The margin is the day's public difficulty, printed beside the room.

   Two measurements decided the shape of it, and neither was a taste call. A jump carries 96 units
   and the ledge-grab already bridges 57, so **no room with a real leap in it measures above 20
   frames**; a blade's open window, by contrast, moves the margin smoothly from 12 to 40. The blade
   is therefore the difficulty dial and the leap a fixed cost — which is also true of the authored
   descent, where only the threshold demands a jump the grab cannot cover. So the week is the
   crossword's: Monday to Wednesday are rhythm rooms by construction, Friday and Saturday ask for
   the leap, Sunday steps back out.

   Three gates, each catching what the others cannot: `RoomGenTest` asserts what a glance would
   have caught (exit on solid ground, abyss below the floor, one idea per screen, narrow enough to
   read whole); `DailyRoomsTest` replays the published plan for **every day of every published
   month** and re-measures its slack, so a tuning change months later fails the build instead of
   quietly turning every difficulty label into a fiction; and `tools/playtest/ghost.js` follows the
   published plan in Chromium, so a browser that can no longer execute the JVM's own answer is a
   red build. `play.yml` curates monthly alongside the Chains grids, and **never rewrites a
   published day** — a ghost link carries a date rather than a room.

2. ~~**Language parity**~~ — done, and published. All eighteen templates are phrased in Russian
   and Hebrew (five were, all Geography), so a run in either language covers the same six
   categories English does. `library.yml` has run for both: **11,267 facts across three languages**
   — English 4,074, Russian 3,936, Hebrew 3,340 — against the ~10 each RU and HE held before.

   Two grammar problems shaped the phrasings and are worth knowing before adding a nineteenth
   template. Wikidata hands back labels in base form only, so Russian cannot decline them —
   every phrasing quotes the name in guillemets and is built to need the nominative. And past
   Geography both languages hit the same second problem from opposite directions: a verb agreeing
   with a painter's or a moon's gender, which no label carries. Both tables keep the sentence's
   grammatical subject a noun written in the file, with the label beside it.

3. ~~**The daily in three languages**~~ — done, which is the part that makes the parity visible.
   `build_chains.py` builds a grid per language from that language's own library, so a Russian
   player gets Russian tiles under Russian group labels rather than translated buttons over an
   English puzzle. 122 grids each in English, Russian and Hebrew. The portal has a switcher, one
   string table with real Russian plurals, and `dir="rtl"` doing the whole of Hebrew's layout;
   `tools/playtest/daily.js` plays all three in Chromium.

   Two things had to be fixed for any of it to work, and neither would have reported itself. The
   tile filter was `[A-Za-z][A-Za-z .'À-ɏ-]*` — a Latin-alphabet test whose range stops at U+024F,
   so **every Cyrillic and Hebrew answer failed it** and a translated build would have published
   nothing while exiting 0. Fixing that then changed all 122 already-published English grids,
   because `build_grid` samples from pools and one new eligible answer reshuffles every date —
   which means the monthly library regeneration had been quietly rewriting played grids all along.
   A published day is now read back and kept verbatim, the rule the daily rooms already followed.

   The catalogue was broken in the same direction: `packs/ru/manifest.json` and its Hebrew twin had
   never existed, so `fetchManifest` 404'd for both languages and the Library tab in them was empty
   while a published pack sat one URL away. `tools/build_manifest.py` writes every catalogue now.

### Wedge 3 (~weeks 6–10): "About anything"  ← BUILT, ONE MERGE FROM RUNNING

1. ~~**Community pack spec + validator**~~ — done. `docs/community-packs.md` is the contract, and
   `tools/validate_pack.py` checks it: everything `ContentParser` enforces, plus the failures that
   parse perfectly and still make a bad pack. Pointed at this repository's own corpus for the first
   time it found **forty questions containing their own answer** — *"Who sculpted Pietà
   (Michelangelo)?"* — now dropped at source by `generate_facts.py`. CI gates every pack on the
   errors and anything under `packs/community/` on the warnings too.

2. ~~**Topic front door**~~ — done, pending one merge. `tools/topic_llm.py` asks a model for the
   two decisions the synonym table could not make: which of the eighteen templates a topic wants,
   and a SPARQL fragment narrowing them. *"Rivers of Africa"* becomes
   `?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .` and harvests African rivers, where the deterministic
   mapper harvested every river and printed that it had ignored the word.

   **The model never writes a fact, a question or an answer.** That line is what makes "about
   anything" safe to say: every published fact is still a Wikidata claim, and a model that phrased
   facts would be a model that could invent them. Five gates run before anything is harvested — a
   closed template list, an allowlisted triples-only grammar, well-formed ids, a **label check**
   that reads the real English label and aliases for every Q- and P-number the model named and
   refuses any whose meaning it got wrong, and a yield floor that drops a narrowing which empties
   its template rather than widening back. All tested offline against a fake model, including the
   hallucinations. Answers cache to `tools/topic_cache.json`, committed, because what a model
   decided a topic meant is a content decision and should be readable in a diff.

   `topic.yml` runs the whole path — dispatch a topic, verify, harvest, validate at the strict bar,
   catalogue it, prove the app's parser can read it, commit. **It cannot be dispatched until it is
   on `main`**: GitHub only offers `workflow_dispatch` for workflows present on the default branch.
   That is the one thing standing between here and a published topic pack.

   The model call itself is proved. `probe.yml` gained a mapper job — the sandbox can reach neither
   `generativelanguage.googleapis.com` nor `query.wikidata.org`, so a real call is only possible
   from a runner — and its first run earned its keep: `gemini-2.5-flash` is retired for new users
   and the API named `gemini-3.6-flash` as its replacement. A 404 naming a successor is now
   followed once and recorded.

3. **Three vertical landing packs** — one published, and the other two turned out to be a
   *content* gap rather than a mapping one, which is worth more than the packs would have been.

   `packs/community/topic-rivers-of-africa-geography.json` is live: **86 facts, 35 distinct
   answers, every one with an image**, narrowed by `?s wdt:P17 ?c . ?c wdt:P30 wd:Q15 .` and
   catalogued so any installed app can download it.

   The plan named a citizenship test, a driving theory test and a fandom. Asked for the first, the
   model answered — correctly — that *"the catalogue lacks templates for civics, government
   structure, political offices, constitutional amendments, national symbols, and US state
   capitals"*. Driving theory is the same story with road signs. **Neither is a mapper failure: no
   template asks those questions, and no narrowing of an existing one can invent them.** The fix is
   a nineteenth template in `generate_facts.py`, which is a content decision worth taking
   deliberately rather than as a side effect of wanting a landing page.

   The fandom is the interesting one. *Star Wars* was attempted five times and failed five times,
   and **each failure was a different real bug**, every one of which is now fixed: a hallucinated
   entity id (Q82347 = "America's Next Top Model, season 2"), a hallucinated *replacement* for it
   (Q809, Polish), a guessed property (`P361`, one fact, where `P8345` links forty-seven), an
   uncaught 429 from Wikidata's rate limiter, and the free tier's daily allowance. The pipeline is
   substantially more robust for having tried it. It still does not publish, and the remaining
   cause is in the harvest rather than the mapping — which the next run will say plainly, because
   topic runs now write their own report into the GitHub run summary instead of burying it in
   post-job git plumbing.

   The lesson underneath all of it is one line: **a model cannot produce Wikidata identifiers, of
   either kind.** Every id it names is now either verified or looked up, and that is what makes it
   safe to let one near published content.

   Three findings came out of these runs and all three are now in the code:

   - **A correct clause can match nothing.** `?s wdt:P30 wd:Q15 .` is exactly "on the continent of
     Africa" and almost no river carries P30. Coverage is invisible to any gate that reads a
     clause, so the endpoint is asked and an empty answer goes back to the model with its row
     count.
   - **A model cannot recall Q-numbers.** Asked about Star Wars it produced `Q82347` — "America's
     Next Top Model, season 2" — and, told so, produced `Q809`, the Polish language. It is no
     longer asked to recall: a refusal now carries Wikidata's own search results and the model
     picks from real candidates. **The label gate is what stood between that and a pack named Star
     Wars full of reality-television credits**, permanently, because a published fact id is
     permanent.
   - **The free tier is twenty requests a day.** Which is the wall this stopped at.

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
3. **Wedges 1 and 2 are done.** What remains of Wedge 1 is not code and is the user's to do: buy
   the domain, publish `.well-known/assetlinks.json` with a release signing fingerprint, and
   create a counter account. One correction on the second of those — Android verifies App Links
   **by host**, so the file must sit at `https://<host>/.well-known/assetlinks.json`. A project
   Pages site at `hillelsht.github.io/polymath/` cannot serve its own host root, so **the domain
   has to come first**, or a repository literally named `hillelsht.github.io` does.

   Wedge 2 is finished end to end: rooms generated and gated three ways, three libraries published
   (11,267 facts), and the daily playable in all three languages with a switcher.

   **Wedge 3's headline works and has published.** `topic.yml` has run end to end: a typed topic,
   mapped by a model, narrowed, harvested from Wikidata, validated at the strict bar, catalogued
   and committed — 86 African rivers, and the pipeline refusing three separate ways along the way.

   Two things are needed to carry on, and both are yours:

   - **Gemini's free tier allows 20 requests a day** and today's are spent. A topic costs one call,
     or two when a proposal needs correcting. Either raise the quota / enable billing on the Google
     AI Studio project (the calls are a few thousand tokens each, so this is cents rather than
     dollars), or add `ANTHROPIC_API_KEY` as a repo secret — the provider is already a
     `--provider anthropic` flag away.
   - **Templates for civics and road signs**, if the citizenship and driving-theory verticals are
     still wanted. That is a `generate_facts.py` change and a content decision, not a mapper one.

   Two content follow-ups worth doing when convenient: the `author` and `musician` pools need
   more facts before those categories can return to the daily, and a device that already
   downloaded the placeholder-element facts keeps them, because library shards only ever add and
   update — deliberately, since deleting facts destroys review history.
4. Branch discipline: work on a feature branch, verify Compose changes by dispatching `build.yml`
   on the branch (this environment cannot compile Android), merge to `main` via PR — merges to
   `main` auto-publish the APK release and the Pages site.
5. Keep the existing gates green: `enginetests` (339 tests), `tools/playtest/play.js` margins,
   `tools/playtest/daily.js`, `tools/playtest/ghost.js`, the docs-freshness pre-push hook (update
   `docs/` when code contradicts it — it will tell you).

Reference links: live site (Polymath) **https://hillelsht.github.io/polymath/** · rolling APK
**https://github.com/Hillelsht/polymath/releases/tag/latest** · post-mortem & game design
`docs/games.md` · browser build `webplay/README.md`.
