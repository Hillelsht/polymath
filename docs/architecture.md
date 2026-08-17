<!-- covers: app/src/main/java/com/hillelsht/smart/SmartApplication.kt, app/src/main/java/com/hillelsht/smart/MainActivity.kt, app/src/main/java/com/hillelsht/smart/ui/**, app/src/main/AndroidManifest.xml, app/src/main/res/values/themes.xml -->

# Architecture

Single-module Kotlin app: Compose + Material 3, MVVM, Room, manual dependency injection. About
17,000 lines of Kotlin across 96 files, plus a separate `enginetests` build that compiles part of
it. One activity, no fragments, no deep links.

```
app/src/main/java/com/hillelsht/smart/
  domain/     Pure Kotlin. Zero Android imports. The learning engine and all game rules.
  data/       Room, remote services, the seeder, and the one repository.
  ui/         Compose screens, one package per screen, plus theme/components/navigation/mascot.
  util/       Locale preference, crash log.
```

## The purity boundary

**`domain/` and `data/seed/` must not import anything Android.** This is the single most
important structural rule in the codebase.

It exists because the `enginetests` build points its source directories straight at those two
folders and compiles them on a plain JVM. That is what makes 292 tests runnable in seconds with
no emulator, testing the exact code that ships. An `android.*` import anywhere in there breaks
that build immediately.

Two consequences that look odd until you know the rule:

- **`Category` carries its own colours as hex strings** (`accentHex`, `secondaryHex`) rather than
  Compose `Color`s. The domain can't import Compose. `Components.kt` converts at the UI edge with
  `Category.accent()`.
- **`Category.displayName` and `.blurb` are plain English strings**, not resource ids — the domain
  can't see generated Android resources either. The same UI-edge pattern applies:
  `Category.localizedName()` maps the enum to `R.string.*`. Any domain enum with user-facing text
  gets a `@Composable` resolver extension rather than a resource id in the enum.

That pattern — *domain holds the data, the UI edge resolves it* — is the answer whenever a domain
type needs something Android-shaped.

## Startup

`SmartApplication.onCreate()`, in order:

1. `CrashLog.install(this)` — **first**, so a crash during startup is still captured.
2. `AppContainer(this)` — constructs everything.
3. `repository.initialise()` launched on an IO scope, so seeding 500+ facts never blocks the
   first frame.

`AppContainer` is manual DI: a handful of constructor calls. No Hilt, no Koin — a DI framework
would add annotation processing for nothing a constructor call doesn't already do. It builds the
database, one shared `OkHttpClient`, the seeder, the three remote services, and the repository.

`USER_AGENT` is mandatory, not decorative: Wikimedia returns 403 to OkHttp's default agent. The
same header is installed as a Coil interceptor, without which every image 403s and falls back to
a gradient.

`MainActivity` is the only activity. It sets the locale in `attachBaseContext` — the earliest
possible hook — because `stringResource()` resolves against whatever context the activity
attaches with, and setting it later leaves the first frame in the previous language. It then
provides `LocalImageResolver` and hosts `SmartApp`.

## Repository

`SmartRepository` is the only class the UI talks to. It joins twelve DAOs, three remote services,
the seeder, and the domain calculators into flows and suspend functions. It is large (~730 lines)
by design: the alternative is a dozen feature repositories that all need the same joins.

Two constructor parameters are seams for testing and configuration rather than dependencies:
`today: () -> LocalDate` and `currentLanguage: () -> Language`. The second is why a content
language change only lands on next launch — see `invariants.md`.

**Failure convention, applied everywhere:** every remote call returns `null` or `false` on
failure, and every caller treats that as "not published yet" rather than an error. Nothing throws
across the network boundary. ViewModels wrap repository calls in `runCatching`.

## ViewModels

Uniform, no DI library. Each has a companion `factory(repository)` returning a `viewModelFactory`,
and its screen calls `viewModel(factory = X.factory(repository))`. The repository is threaded down
as an ordinary composable parameter from `SmartApp`.

Two shapes, chosen by what the screen is:

- **Flow-composed** (Home, Library, Stats, Watch) — `combine(...).stateIn(viewModelScope,
  SharingStarted.WhileSubscribed(5_000), Default())`.
- **Single mutable state** (the games) — one `MutableStateFlow<UiState>` plus `asStateFlow()`,
  because a game is a state machine being driven, not a projection of the database.

## Navigation

`SmartNavHost.kt` holds every route in `object Routes`, plus builders for the three parameterised
ones (`quiz`, `category`, `player`). Fifteen destinations; six are tabs.

The bottom bar renders only when the current route is one of the six tabs — study flows and games
take the whole screen deliberately. Tab labels are pinned to `fontSize = 10.sp, maxLines = 1,
softWrap = false`: Russian and Hebrew labels run wider than English, and a wrapped second line
changes one item's height relative to its neighbours, so the whole bar visibly jumps as you
switch tabs.

**Two destinations take deep links**, both dailies: `Routes.CHAINS_LINKS` and
`Routes.VAULTS_LINKS` are attached to their `composable`s with `navDeepLink`, and mirrored by
intent-filters in the manifest — the manifest cannot read Kotlin, so changing one without the
other is the trap.

Each has two schemes, doing different jobs. **`polymath://daily/chains`** works the moment the app
is installed: a custom scheme needs no verification and no agreement with any server, which is
exactly what a sideloaded build has. **`https://hillelsht.github.io/smart/chains.html`** is the one
that matters — it makes a shared daily open the app for someone who has it and the site for
someone who does not — and it is **inert today**. Android 12 and later ignore an unverified https
filter outright, and verification needs `.well-known/assetlinks.json` on the site carrying the
release signing fingerprint, which no debug build has. It is declared with `autoVerify="true"` so
that publishing that file is the only remaining step.

The https paths are the pages the site actually serves rather than tidier ones invented for the
manifest, because the link someone has in their hand is the one they copied from the address bar.

Everything else is in-app; all parameterised navigation still is.

## UI conventions

- **Theme** — `SmartTheme` with a hand-written palette and type scale. Dynamic colour (Material
  You) is deliberately **not** used: category identity (Geography teal, History amber, Science
  blue) is the app's main navigational cue, and repainting it from the user's wallpaper destroys
  it.
- **Shared components** live in `ui/components/Components.kt`: `SmartCard`, `MasteryRing`,
  `FactImage`, `CategoryChip`, `StatTile`, `EmptyState`, `SessionProgress`, `GoDeeper`.
- **Images** resolve through `LocalImageResolver`, a `fun interface` whose default never resolves
  — so Compose previews fall back to gradients instead of hitting the network. `FactImage`
  distinguishes "no picture" from "picture failed to load", because a silent fallback once made a
  403 look identical to a fact with no image and hid a broken path for an entire release.
- **Scrollable tabs use `bottom = 96.dp` content padding.** That clears the mascot's 64dp strip
  plus margin. If a list scrolls under Aryeh, this is why.

## The mascot

Aryeh is a lion drawn entirely with Compose vector paths — `AryehArt.kt` holds ~30 SVG path
strings, `AryehRig.kt` poses them, `MascotHost.kt` animates and places them. No image assets, so
he costs nothing in APK size.

`MascotDirector` (in `domain/`, and therefore tested) decides what he does: a repertoire per
surface, a duration range per activity, no immediate repeats, and a nap after 45 seconds idle.
He appears on tab screens only — never inside Learn, Review, or a running game, where he would
be in the way.
