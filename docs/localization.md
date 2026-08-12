<!-- covers: app/src/main/res/values/**, app/src/main/res/values-ru/**, app/src/main/res/values-he/**, app/src/main/res/values-iw/**, app/src/main/java/com/hillelsht/smart/domain/model/Language.kt, app/src/main/java/com/hillelsht/smart/util/LocalePrefs.kt, packs/he/**, packs/ru/** -->

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
2. Create `res/values-<tag>/strings.xml` with all 219 keys. Everything else is enum-driven and
   needs no code change: Settings lists `Language.entries`, and the seeder scans
   `assets/packs/<tag>/` for every non-default language automatically.
3. Add a translated content pack — `packs/<tag>/geography.json` — with a **suffixed `packId` and
   suffixed fact ids**, and bundle a copy at `app/src/main/assets/packs/<tag>/`. Both, or it
   ships nowhere. See `invariants.md`.
4. Add channels to `assets/content/channels.json` with `"language": "<tag>"`, covering all six
   categories. CI's prober resolves the handles.
5. Optionally add generator phrasings (below).

`ChannelLanguageTest` enforces that every language offered in Settings has a Watch channel in
every category — a language with an empty category is a dead filter chip, where the user taps
and sees nothing with no way to tell whether it's broken.

## Right-to-left

RTL needed **no new layout code**. Android derives layout direction from the locale, the manifest
already declares `supportsRtl`, no code overrides `LocalLayoutDirection`, no layout hardcodes
`left`/`right` instead of `start`/`end`, and the single directional icon in the app already used
the `AutoMirrored` variant.

Canvas-drawn UI — the mascot rig, the chess board — uses raw coordinates and is *not* mirrored.
That is correct: a chess board should not flip.

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

Only the five Geography templates are translated so far. `templates_for(language)` drops any
template with no phrasing for that language, so a run in Russian or Hebrew publishes fewer
categories rather than half-English facts. Adding a phrasing is the only work needed to extend
coverage.

Language-suffixed ids apply here too: `wd-capital-Q142` in English,
`wd-capital-Q142-he` in Hebrew. English keeps its exact historical id shape so nothing already
installed on a device is orphaned.
