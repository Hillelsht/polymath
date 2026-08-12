// Stands in for a compiler.
//
// This project is developed in a sandbox with no route to dl.google.com, so the Android SDK,
// Compose and Room can never resolve here and `assembleDebug` cannot run at all — CI is the only
// real compiler (see .github/workflows/build.yml). That leaves a multi-minute round trip to
// discover a typo, which is the gap this closes.
//
// It compiles the app's *real* sources with only the Kotlin stdlib, coroutines and serialization
// on the classpath. Everything androidx-shaped fails to resolve and is expected to — run the
// output through `tools/compile_scan.py`, which buckets those away and surfaces only errors it
// cannot explain.
//
// What it catches: syntax errors, type mismatches, bad overload resolution, and anything in the
// pure-Kotlin domain layer. What it does NOT catch: Compose compiler errors, Room annotation
// processing, and anything that needs a resolved androidx symbol. Those still only appear in CI.
//
// coroutines earns its place on the classpath: the ViewModels are mostly Flow plumbing, and a
// `combine` whose lambda Kotlin cannot infer looks identical to an unresolved androidx symbol
// unless Flow itself resolves.
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

repositories { mavenCentral() }

// Relative to this file, so the harness travels with the repository rather than with one machine.
sourceSets { main { kotlin.srcDirs("../../app/src/main/java") } }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
