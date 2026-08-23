<!-- covers: tools/**, .github/workflows/**, packs/** -->

# The content pipeline

Almost nothing the app teaches is written by hand, and almost nothing it teaches ships in the
APK. Content is generated or enriched in CI, committed to `packs/`, and served to installed apps
from `raw.githubusercontent.com` — effectively a free CDN for a public repo. That is why the APK
stays flat while the library grows past 3,500 facts.

**Everything here runs in CI and cannot run in the development sandbox**, which has no route to
Wikidata, Wikipedia or YouTube. Every tool is stdlib-only Python; there is no
`requirements.txt` and nothing to install.

## The tools

| Tool | Produces | Run by |
|---|---|---|
| `generate_facts.py` | `packs/library/` — the unbounded fact library, from Wikidata | `library.yml`, monthly |
| `enrich_content.py` | `packs/*.json` — hand-authored facts + Wikipedia images/extracts | `content.yml` |
| `enrich_videos.py` | `packs/channels.json` — the probed Watch allowlist | `content.yml` |
| `fetch_durations.py` | `packs/durations.json` — video lengths, via the YouTube API | `durations.yml`, hourly |
| `build_chains.py` | `packs/play/chains/` — daily puzzle grids, one set per language | `play.yml`, monthly |
| ↳ | *also decides which answers are fit to be tiles — see `docs/games.md`* | |
| `enginetests publishRooms` | `packs/play/vaults/` — the daily room's seed, margin and plan | `play.yml`, monthly |
| `validate_pack.py` | nothing — reads packs and reports | run by hand, and by CI over every pack |
| `build_manifest.py` | `packs/manifest.json`, `packs/<tag>/manifest.json` — the catalogue | `content.yml`, `topic.yml`; checked by `build.yml` |
| `topic_pack.py` | `packs/community/` — a pack built from a typed topic | `topic.yml`, on dispatch |
| `topic_llm.py` | `tools/topic_cache.json` — what a model decided a topic meant | called by `topic_pack.py --llm` |
| `probe_durations.py` | nothing — findings only | `probe.yml`, manual |
| `probe_wikidata.py` | nothing — findings only | `probe.yml`, manual |
| `playtest/play.js` | nothing — plays The Vaults in Chromium, screenshots | `web.yml` |
| `playtest/daily.js` | nothing — plays the daily grid in Chromium, screenshots | `web.yml` |
| `playtest/ghost.js` | nothing — runs the daily room, then races the link it produces | `web.yml` |
| `playtest/serve.js` | nothing — a static server, so `daily.js` gets a real origin | `web.yml` |
| `playtest/inline.py` | one self-contained HTML file of The Vaults | `web.yml` |

`validate_pack.py` is the odd one in the other direction: it is the only tool here that writes
nothing. It exists for Wedge 3, where the next content arrives as a pull request from someone this
repository has never met, so the contract a pack must meet has to be checkable by a machine. It
enforces everything `ContentParser` does and then the things that parse perfectly and still make a
bad pack — an answerType too thin to draw distractors from, a translated fact id missing its
language suffix, a question containing its own answer. Errors mean the pack would misbehave;
warnings mean it would work and could be better; `--strict` promotes them.

`build_manifest.py` writes the **catalogue**, and it is worth knowing why that is a tool of its
own rather than three lines at the end of `enrich_content.py`, where it used to live.
`PackService` reads exactly two files to discover content: `manifest.json` for the packs somebody
chooses from, and `library/index.json` for the shards that top themselves up. A pack in neither is
bytes on a CDN that nothing will ever request. Building the catalogue from `assets/content/*.json`
— the six hand-authored sources — therefore made a whole class of pack *unpublishable*: a topic
pack in `packs/community/` could be added to the manifest by hand and would survive exactly until
the next content run deleted it, which is the worst version of that bug because it works when you
test it. It also meant Russian and Hebrew had no catalogue at all, for weeks, with a published
pack one URL away. Now every run catalogues what is actually published, in every language, and
`build.yml` runs `--check` so a manifest that has drifted fails a build instead of a phone.

It also stamps a `version` into any published pack that names none. `ContentParser` falls back to
hashing the raw text, which nothing outside the app can reproduce, so the catalogue and the device
would disagree about the version forever — and a device that believes its pack is stale offers the
same download on every refresh for as long as the app is installed.

**One pipeline is not in `tools/` and cannot be.** Curating a daily Vaults room means running
`Playtest.solve` over each candidate a few thousand times, and the physics is Kotlin, so the
publisher is a Gradle task over the engine tests' classpath —
`gradle -p enginetests publishRooms -Pmonths=4`, next to the tests that check its output. Everything
else about it is the same bargain the Python tools make: deterministic, offline, gated before the
commit. See `docs/games.md` for what a band is and why a published day is never rewritten.

`tools/playtest/` is the odd one out in the other direction: it produces no content, and its
scripts are the only Node here rather than Python, because they drive a browser. It exists because Aryeh's Palace shipped
unplayable past a full suite of headless tests — nothing had ever pressed a button. See
`docs/games.md`.

`probe.yml` carries a third question of the same kind: **what a real model makes of a topic**.
The mapper's gates are all tested offline against a fake model, which proves they refuse what they
should and proves nothing about whether the request shape is right or whether a real model's
answers survive gates nobody wrote them to satisfy — and neither is knowable from a sandbox whose
egress policy refuses `generativelanguage.googleapis.com` and `query.wikidata.org` alike. Its first
run earned its keep immediately: the model name was a guess made where it could not be checked, and
the API answered 404 — `gemini-2.5-flash` retired, `gemini-3.6-flash` named as its replacement. A
404 that names a successor is now followed once and recorded, because a model name has a shelf life
measured in months and this pipeline runs monthly.

The two probes are reconnaissance scripts whose **results are recorded in their own docstrings**.
`probe_durations.py` establishes that neither the channel RSS feed nor the embed page carries a
duration, so the YouTube Data API is the only source — which is the entire justification for
`fetch_durations.py` and its API key. Read the docstring before re-litigating that.

## generate_facts.py

The interesting one. A fact here is a **property, not a sentence**: "capital of France" is P36 on
Q142, so the generated fact is true by construction rather than by parsing. A property *is* an
`answerType`, so distractors come out same-typed for free. Sitelink counts measure importance, so
facts can be delivered most-important-first.

Three constraints found by probing rather than reasoning, all of which shape the code:

- **Broad SPARQL queries time out.** The public endpoint allows roughly 60 seconds and cannot
  rank 865,300 paintings inside it. The fix is an importance floor, retried up the ladder
  `[0, 15, 30, 60, 120, 250]` — which shrinks the candidate set *and* selects for importance,
  which is wanted anyway. Three attempts maximum; a template that timed out twice will not
  squeak through on a third nudge.
- **The sandbox cannot reach Wikidata**, so no Q- or P-number can be verified where it is
  written. `preflight()` checks each id's shape offline, then asks Wikidata for its label and
  prints what each one actually means. A wrong id becomes a named line in the CI log instead of a
  template that silently yields zero.
- **Wikidata labels come back in base form only.** No case, no declension. This is what shapes
  the translated phrasings — see `localization.md`.

**A question must not contain its own answer.** The first run of `validate_pack.py` over the
published library found forty that did: *"Who sculpted Pietà (Michelangelo)?"*, *"Which body does
moon of Saturn orbit?"*, *"What is the capital of Guinea-Bissau?"*. Every one is true and parses
perfectly, and every one prints the answer inside the question and then marks three distractors
wrong — so a player who knew nothing scores what a player who knew everything does. The cause is
structural rather than unlucky: Wikidata labels carry their own disambiguation and the templates
slot them in whole. `drop_leaks()` removes them before `prune()`, since losing them can be what
takes an answerType below the quiz's floor, and `validate()` refuses them again at publish —
because a fact that answers itself is invisible in a diff and permanent on a device, given that
library shards only ever add and update.

**True by construction is not the same as worth asking.** The library shipped "What is the
chemical symbol for unquadoctium?" — element 148, which nobody has made. Wikidata holds the claim
and types it exactly like tantalum's, so every guard above passed it; a few of these even carry
enough sitelinks to clear any sensible importance floor. The only thing that separates them is
that every element which exists has a one- or two-letter symbol, so a three-letter one is always
an IUPAC placeholder. A `Template` can therefore carry a `valid` predicate — its last word on its
own answers, for what a SPARQL constraint cannot express and sitelinks do not catch. It lives in
Python rather than in the query so it is tested here, offline, in a second, instead of being
found out by a forty-minute harvest.

Publishing is guarded. It refuses if fewer than 400 facts survive, or if the total drops below
60% of the previous run, or if any answerType is too thin to hold a quiz (fewer than 8 facts or
4 distinct answers). A weak template costs its own facts, not everybody else's: `prune()` drops
it and the rest still publishes. That rule exists because the first full run harvested 3,223
facts and published none of them, vetoed by two templates that yielded one fact each.

## The workflows

Eight, of which **five commit back to `main`**.

| Workflow | Trigger | Commits | Runs tests |
|---|---|---|---|
| `build.yml` | push / PR | no — publishes the `latest` release | yes |
| `content.yml` | push to `assets/content/**`, weekly | `packs/`, assets mirror, every manifest | no |
| `durations.yml` | hourly | `packs/durations.json` | no |
| `library.yml` | monthly, or dispatch | `packs/library/`, `packs/<tag>/library/` | yes, before committing |
| `play.yml` | monthly, dispatch, or a change to the room grammar | `packs/play/` | yes, before committing |
| `probe.yml` | manual only | nothing | no |
| `topic.yml` | dispatch only | `packs/community/`, the manifests, the topic cache | yes, before committing |
| `web.yml` | push / PR touching a game or its packs, or dispatch | no — publishes the portal to GitHub Pages | yes, and plays both |

Shared idioms, each of which is load-bearing:

- **Self-retrigger guards.** Every self-committing workflow skips when the head commit message
  contains its own tag (`[content-pipeline]`, `[durations]`, `[library]`, `[play]`). That is why
  bot commit subjects are fixed strings — the format is functional, not cosmetic.
- **Generators self-test before they run.** `library.yml` runs `--self-test` first so a logic bug
  fails in seconds rather than after a 40-minute harvest.
- **Engine tests gate publication.** `library.yml` and `play.yml` run `gradle -p enginetests test`
  against the content they just generated, *before* committing it. `GeneratedLibraryTest` parses
  new shards with the app's real parser, `TranslatedLibraryTest` does the same for every other
  language and additionally refuses a fact id missing its language suffix — such an id installs
  *over* the English fact and takes its review history with it — `PlayContentTest` applies the
  device's own rules to every grid, and `DailyRoomsTest` re-measures every published room's timing
  slack against the physics that build ships. Schema drift, a fact id that would eat another, an
  ambiguous puzzle or a room whose difficulty label has quietly become a fiction all fail before
  the commit rather than after it. When that gate does fire in `library.yml`, the content that
  failed is never committed and there is nothing left to inspect — so the run uploads the test
  report as an artifact, and the engine tests print the full assertion rather than the test's name.
- **The content pipeline rebases and retries its push, three times.** A human push landing
  mid-run once made the push a non-fast-forward and threw away a full probe of 54 channels.
- **`enrich_videos.py` is `continue-on-error`.** YouTube sometimes refuses datacenter IPs. The
  prober writes nothing unless it got a usable answer, so a bad run leaves the previous allowlist
  intact — and must not hold back the Wikipedia enrichment that already succeeded.

`library.yml` runs **one language at a time** — a matrix with `max-parallel: 1`, because each job
commits to `main` and three racing would spend the run rebasing each other, and `fail-fast: false`,
because a Russian timeout must not cancel a Hebrew harvest that was going to succeed. Each job
commits only its own language's directory and rebases-and-retries its push three times, since a
long harvest is too expensive to throw away over a non-fast-forward.

Separate jobs are not only about the push. A run costs **3 minutes in English, 30 in Russian and 10
in Hebrew**, and the difference is not the SPARQL — the harvest is two minutes in every language.
It is `extracts_for()`: ru.wikipedia's API answers a 20-title batch roughly ten times slower than
en's. Three languages in one job would not fit the 75-minute timeout, and the language that blew it
would take the other two down with it.

**Bot commits cannot trigger other workflows.** See `invariants.md` — this is a GitHub
anti-recursion rule, and it means a Build run for a pipeline commit needs a manual dispatch.

## The Watch allowlist

The app **never** lists video ids. It lists *channels*; the pipeline resolves each handle to its
stable `UC…` id and verifies a sample of its videos are embeddable; the app then discovers actual
videos live from each channel's public RSS feed.

This design is a direct response to a failure: 187 hand-written video ids turned out to contain
30 real ones, most pointing at unrelated videos. Ids are now real by construction.

Embeddability has to be probed separately because a feed says nothing about it, and a channel
that forbids embedding produces YouTube error 152 — a card that looks fine and fails on tap.

Size guards apply to **English channels only** (at least 12 usable, at least 3 per category);
other languages are counted and reported but not gated, so a language whose channels are still
being established cannot turn the build red.

## Repository size

Generated content committed on a schedule accumulates in history permanently. `packs/library/`
alone is 3.8 MB across 28 shards, JSON is 87% of tracked bytes, and the hourly durations job is
the highest-frequency writer.

Two things this is *not*:

- The six pack files that appear in both `packs/` and `app/src/main/assets/packs/` are
  byte-identical, and git is content-addressed, so it **already stores them once**. The
  duplication costs working-tree space, not repository space, and it is deliberate: the bundled
  copy is what makes a fresh install work offline. Do not "fix" it.
- Loose-object bloat is a local `git gc` matter, not a content matter.

The real lever, if this ever needs pulling, is publishing generated shards as **release assets**
rather than repo files — `PackService` would need a different base URL, and history would stop
growing by several MB a year. That is a real architecture decision, deliberately not taken yet,
and recorded here so the option is visible rather than rediscovered.
