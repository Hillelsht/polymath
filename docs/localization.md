<!-- covers: tools/build_chains.py, webplay/web/polymath.js, app/src/main/res/values/**, app/src/main/res/values-ru/**, app/src/main/res/values-he/**, app/src/main/res/values-iw/**, app/src/main/java/com/hillelsht/smart/domain/model/Language.kt, app/src/main/java/com/hillelsht/smart/util/LocalePrefs.kt, packs/he/**, packs/ru/** -->

# Localization

Three languages: English, Russian (`ru`), Hebrew (`he`). Hebrew is right-to-left.

"Language" means two independent things here, and they behave differently:

| | Switches | Source |
|---|---|---|
| **UI strings** | immediately, on `Activity.recreate()` | `res/values-<tag>/strings.xml` |
| **Content** (facts, videos) | **on next app launch** | language-scoped packs and channel rows |

The content lag is a known, documented limitation, not a bug awaiting a fix: `SmartRepository`
captures `currentLanguage()` once and `AppContainer` is built once per process, while Settings
only recreates the activity.

## How a language is applied

`LocalePrefs` stores the tag in plain SharedPreferences — deliberately not DataStore, because
`MainActivity.attachBaseContext` needs the answer **synchronously, before the first frame**, and
a suspend or Flow-based store cannot answer in time. `Context.withLanguage()` builds a
configuration context from it.

`Language.tag` is a **wire format**: the persisted preference value, the Android resource
qualifier, and the `packs/<tag>/` path segment all at once. Renaming one orphans every saved
preference and every published path.

## Adding a language

1. Add the enum entry — `Language.tag` and a `displayName` **in its own script** (`Русский`,
   `עברית`), never translated.
2. Create `res/values-<tag>/strings.xml` with all 214 keys — every key in `values/` except
   `app_name`, which is deliberately not translated. Everything else is enum-driven and
   needs no code change: Settings lists `Language.entries`, and the seeder scans
   `assets/packs/<tag>/` for every non-default language automatically.
3. Add a translated content pack — `packs/<tag>/geography.json` — with a **suffixed `packId` and
   suffixed fact ids**, and bundle a copy at `app/src/main/assets/packs/<tag>/`. Both, or it
   ships nowhere. See `invariants.md`.
4. Run `python3 tools/build_manifest.py`, which writes `packs/<tag>/manifest.json`. Without it the
   pack ships in the APK and is **undownloadable**: `PackService.fetchManifest` asks for exactly
   that path, a 404 comes back as `null`, and `null` is also what "CI has not published yet" looks
   like — so the Library tab in that language is empty and nothing anywhere says why. Russian and
   Hebrew both shipped like that for weeks with a published pack sitting one URL away. The same
   run stamps a `version` into any pack that names none, because `ContentParser` otherwise falls
   back to hashing the raw text, which no tool outside the app can reproduce — leaving the
   catalogue and the device permanently disagreeing about whether the pack is current, and the
   device re-downloading it on every refresh. `CatalogueTest` fails the build on both.
5. Add channels to `assets/content/channels.json` with `"language": "<tag>"`, covering all six
   categories. CI's prober resolves the handles.
6. Add generator phrasings for all eighteen templates (below). The self-test requires them.
7. Add the language to `tools/build_chains.py` (`LANGUAGES`, and a name for every entry in
   `LABELS`) and to `webplay/web/polymath.js` (`LANGUAGES`, `LANGUAGE_NAMES`, and a full row in
   `STRINGS`). Both self-tests assert parity by name, so a half-added language fails rather than
   shipping with English showing through.

A topic pack follows the same rule, and it is where the rule was learned the hard way. English
lands at `packs/community/`, every other language at `packs/<tag>/community/` — and the folder must
agree with the pack's declared `language`, because `build_manifest.py` reads the field rather than
the path. A translated pack in the English folder is declined by English (it says `ru`) and never
seen by Russian (which does not look there), so it is published, served, and unreachable. The first
multilingual topic run did precisely that with 81 Russian and 34 Hebrew facts and reported success;
`build_manifest.py --check` now fails on any fact pack no catalogue claims.

## The daily, in three languages

This is the claim the whole product rests on — "playable in your language" — and it is the one a
wordplay daily structurally cannot copy, because MOUSE/TRAP/CHEESE does not survive translation.

It works because the *content* is translated, not only the chrome. `build_chains.py` builds a grid
per language from that language's own library, so a Russian player gets Russian tiles under Russian
group labels. English publishes at `packs/play/chains/`, everything else at
`packs/play/chains/<tag>/` — the unprefixed-English convention used everywhere else here.

Consequences worth knowing before touching it:

- **The grids are different puzzles on the same day**, not one puzzle translated. Three corpora
  that do not hold the same facts cannot share a grid without reducing every language to what the
  thinnest supports. The share text names the language for exactly this reason.
- **A saved game is keyed by language.** A saved Chains game is a list of guesses, replayed
  through the rules to rebuild the board; replaying an English guess into a Russian grid selects
  tiles that are not on it. English keeps its unsuffixed `localStorage` key so no existing streak
  resets, and streaks are per-language, which is right — they are different puzzles.
- **`dir="rtl"` on the root element is the whole of Hebrew's layout support**, on the web exactly
  as in the app. The browser mirrors flex and grid; the emoji share block is pinned back to `ltr`
  because it is a picture rather than a sentence.
- **The tile filter must stay Unicode-aware.** It used to be `[A-Za-z][A-Za-z .'À-ɏ-]*`, whose
  range stops at U+024F, so every Cyrillic and Hebrew answer failed it and a translated build
  would have published nothing while exiting 0. `readable_tile` uses `str.isalpha()` now, and the
  self-test names all three scripts.

`tools/playtest/daily.js` plays the Russian and Hebrew dailies through the page's own buttons in
Chromium, and checks the three things that can each be true while the feature is broken: that the
tiles are in the language asked for, that the chrome is, and that it is not the English grid
relabelled.

`ChannelLanguageTest` enforces that every language offered in Settings has a Watch channel in
every category — a language with an empty category is a dead filter chip, where the user taps
and sees nothing with no way to tell whether it's broken.

## Right-to-left

RTL needed **no new layout code**. Android derives layout direction from the locale, the manifest
already declares `supportsRtl`, no code overrides `LocalLayoutDirection`, no layout hardcodes
`left`/`right` instead of `start`/`end`, and the single directional icon in the app already used
the `AutoMirrored` variant.

Canvas-drawn UI — the mascot rig, the chess board, the Vaults' rooms — uses raw coordinates and
is *not* mirrored. That is correct: a chess board should not flip, and a platformer whose exit is
on the right should not put it on the left in Hebrew.

## The `values-iw` duplicate

**`values-he/strings.xml` and `values-iw/strings.xml` are identical and must be edited together.**

Android's resource-qualifier matching for Hebrew never fully settled on `he` over the deprecated
`iw`. RTL and locale logic resolve `he` correctly, because those go through a different,
string-tag-based check — so on device the layout mirrored correctly and Hebrew *content* from
JSON displayed correctly, while **every UI string silently fell back to English**. Shipping both
files removes the ambiguity for a few KB.

## Translating content

A translated pack keeps `wikiTitle` in English (it addresses the English Wikipedia article, which
is what supplies the image) and keeps `answerType` in English (it is the distractor grouping key,
not display text). Everything a reader sees — `title`, `statement`, `question`, `answer`, `hook`,
`details` — is translated.

Gender is worth being careful about. The Hebrew UI strings use infinitives and gerund nouns for
actions, and second-person plural or first-person past for body text — all spelled identically
regardless of the reader's gender in unvocalized Hebrew — so nothing assumes a gender for the
learner.

## Generator phrasings, and the grammar problem

`generate_facts.py` builds sentences by slotting Wikidata labels into templates. **Wikidata
returns labels in base form only** — nominative, undeclined, with no grammatical metadata. A
template cannot know how to inflect an arbitrary name it has never seen.

Each language solves that differently, and the solutions are the reason the translations are not
literal:

- **Russian** wants genitive or locative for most of these phrasings ("столица Франции", "в
  Европе"). `RU_PHRASING` sidesteps it by quoting the proper noun in guillemets and restructuring
  the sentence so the label only ever appears in nominative: `«Париж» — столица страны
  «Франция».`
- **Hebrew**'s problem is different — not case but *construct state*, where the first noun of a
  possessive pair inflects (`בירה` → `בירת`). That noun is always one the template author writes,
  never the Wikidata label, so it would be safe even unhandled. `HE_PHRASING` uses the `X של Y`
  construction anyway, which needs no inflection on either side and works regardless of gender or
  number.

Beyond Geography both languages meet the same second problem, from opposite directions: **a verb
agreeing with something the template has never seen**. Russian's «открыл» agrees with the
discoverer, Hebrew's «נשפך» with the subject, and a painter's or a moon's gender is not a thing a
label carries. Both tables solve it the same way — keep the sentence's grammatical subject a noun
written *here* (`Автор картины`, `המחבר`, `הקרב`), with the Wikidata label quoted beside it or in a
`של` phrase. The sentence then agrees with a word whose gender is known, and the label is slotted
in exactly as given.

**All eighteen templates are translated into both languages**, so a Russian or Hebrew run covers
the same six categories English does. The self-test asserts that parity rather than leaving it to
be noticed — add an English template without translating it and `--self-test` fails by name. The
graceful path still exists underneath: `templates_for(language)` drops any template with no
phrasing, so a language could publish fewer categories rather than half-English facts.

A phrasing must never put `{o}` in the title or the question. That is the one mistake that turns a
fact into an anti-fact — the quiz would print the correct answer inside its own question and mark
three distractors wrong for no reason — and it is impossible to spot by reading a language you do
not have, so the self-test checks every phrasing in every language.

Language-suffixed ids apply here too: `wd-capital-Q142` in English,
`wd-capital-Q142-he` in Hebrew. English keeps its exact historical id shape so nothing already
installed on a device is orphaned.
