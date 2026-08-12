// A standalone build, deliberately not included from the root settings.gradle.kts. Run it with
// `gradle -p tools/parsecheck compileKotlin`, which switches the build root the same way
// `gradle -p enginetests test` does.
dependencyResolutionManagement { repositories { mavenCentral() } }

rootProject.name = "parsecheck"
