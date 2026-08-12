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
                // The shipping physics, compiled here unchanged. This is the whole point: the
                // browser runs the same code the phone runs, so tuning done here is tuning done
                // for real. It works because domain/play/vaults is Kotlin-stdlib only — no
                // `java.*`, no `android.*`. A single java.time import would break this build.
                "../app/src/main/java/com/hillelsht/smart/domain/play/vaults",
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
 * Assembles a runnable page in `build/web`: the compiled bundle plus the static renderer.
 *
 * Deliberately stops short of webpack, which would pull the npm toolchain in for no gain — the
 * Kotlin compiler already emits loadable UMD modules, and the page includes them with plain
 * <script> tags.
 */
val bundle by tasks.registering(Copy::class) {
    dependsOn("compileDevelopmentExecutableKotlinJs")
    from("build/compileSync/js/main/developmentExecutable/kotlin") { include("*.js", "*.map") }
    from("web")
    into(layout.buildDirectory.dir("web"))
}

tasks.named("build") { dependsOn(bundle) }
