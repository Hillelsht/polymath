// Imported rather than written out at the call site: inside a Kotlin DSL build script `java`
// resolves to the Java plugin extension, so `java.time.YearMonth` does not compile.
import java.time.YearMonth

plugins {
    kotlin("multiplatform") version "2.0.21"
}

kotlin {
    js(IR) {
        browser()
        binaries.executable()
    }

    sourceSets {
        jsMain {
            kotlin.srcDirs(
                // The shipping game rules, compiled here unchanged. This is the whole point: the
                // browser runs the same code the phone runs, so tuning done here is tuning done
                // for real. It works because these packages are Kotlin-stdlib only — no
                // `java.*`, no `android.*`. A single java.time import would break this build.
                "../app/src/main/java/com/hillelsht/smart/domain/play/vaults",
                "../app/src/main/java/com/hillelsht/smart/domain/play/chains",
                "src/jsMain/kotlin",
            )
        }
    }
}

// The sandbox has no route to nodejs.org, and does not need one: Node 22 is already installed.
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().download = false
}

/**
 * Bakes both published dailies — the Chains grids and the Vaults rooms — into one script the page
 * can read with no network at all.
 *
 * The obvious design is to fetch this month's JSON. It does not survive contact with how this
 * project is actually checked: `tools/playtest/play.js` opens the page over `file://`, where a
 * `fetch` of a sibling file is refused as a cross-origin request, so the daily could never be
 * driven end-to-end in CI. Baking it in also means the daily paints without waiting for anything.
 *
 * The page still falls back to the network for a month it was not built with, and it has to: a
 * pack refreshed by a bot cannot trigger a rebuild of this site (bot pushes do not start
 * workflows), so a deploy left alone for long enough would otherwise run out of days.
 *
 * The window starts one month back so yesterday's daily is always available — a streak is read
 * from the days around today, not from today alone.
 */
val dailies by tasks.registering {
    // One file per game rather than one between them: the grids run to a few hundred kilobytes of
    // Wikidata labels and the rooms to a few dozen numbers, and the descent has no use for the
    // grids. Each page pays for its own daily and nobody else's.
    val games = mapOf(
        "dailies.js" to ("POLYMATH_CHAINS" to layout.projectDirectory.dir("../packs/play/chains")),
        "rooms.js" to ("POLYMATH_VAULTS" to layout.projectDirectory.dir("../packs/play/vaults")),
    )
    val outDir = layout.buildDirectory.dir("generated")
    games.values.forEach { inputs.dir(it.second) }
    outputs.dir(outDir)
    doLast {
        val from = YearMonth.now().minusMonths(1).toString()
        games.forEach { (name, game) ->
            val (global, packs) = game
            val months = (packs.asFile.listFiles().orEmpty())
                .filter { it.name.endsWith(".json") && it.nameWithoutExtension >= from }
                .sortedBy { it.name }
            months.forEach { month ->
                // A closing script tag would end the page's <script> early and truncate the day
                // into something that still parses. Chains content comes from Wikidata labels, so
                // this is unlikely rather than impossible, and silent corruption is hard to spot.
                require("</script" !in month.readText().lowercase()) { "${month.name} cannot be inlined" }
            }
            val body = months.joinToString(",\n  ") { "\"${it.nameWithoutExtension}\": ${it.readText()}" }
            outDir.get().file(name).asFile.apply { parentFile.mkdirs() }
                .writeText("globalThis.$global = {\n  $body\n};\n")
            logger.lifecycle("$name: ${months.size} month(s) from $from")
        }
    }
}

/**
 * Assembles a runnable site in `build/web`: the compiled bundle plus the static pages.
 *
 * Deliberately stops short of webpack, which would pull the npm toolchain in for no gain — the
 * Kotlin compiler already emits loadable UMD modules, and the pages include them with plain
 * <script> tags. The pages share one bundle and one stylesheet, so the portal costs a second
 * page-load nothing.
 */
val bundle by tasks.registering(Copy::class) {
    dependsOn("compileDevelopmentExecutableKotlinJs", dailies)
    from("build/compileSync/js/main/developmentExecutable/kotlin") { include("*.js", "*.map") }
    from(dailies)
    from("web")
    into(layout.buildDirectory.dir("web"))
}

/**
 * What actually gets published: `build/web` without the things only a developer wants.
 *
 * Source maps are 350 KB of no use to a player, and the playtests drop their screenshots into the
 * same directory — a published site should not quietly include a picture of its own test run.
 */
val site by tasks.registering(Copy::class) {
    dependsOn(bundle)
    from(layout.buildDirectory.dir("web")) { exclude("*.map", "*.png") }
    into(layout.buildDirectory.dir("site"))
}

tasks.named("build") { dependsOn(bundle) }
