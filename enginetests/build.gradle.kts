plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

// Intentionally unpinned: this harness runs on whatever JDK the developer has, while the app
// module targets Java 17 for Android. The shared sources are plain Kotlin, so both work.

/**
 * Point at the app's real sources rather than copying them. The domain layer and the content
 * parser are deliberately free of Android imports, so they compile here unchanged — these
 * tests therefore exercise the exact code that ships in the APK.
 */
sourceSets {
    main {
        kotlin.srcDirs(
            "../app/src/main/java/com/hillelsht/smart/domain",
            "../app/src/main/java/com/hillelsht/smart/data/seed",
        )
    }
    test {
        kotlin.srcDirs("src/test/kotlin")
        // The curriculum itself, loaded straight from the app's assets.
        resources.srcDirs("../app/src/main/assets")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

/**
 * Curates the daily Vaults rooms and writes them to `packs/play/vaults/`.
 *
 * `gradle -p enginetests publishRooms -Pmonths=4`
 *
 * A `JavaExec` over the test classpath rather than a test, because it writes files: a test that
 * edits the repository is a test you cannot run twice with confidence. `DailyRoomsTest` is the
 * half that belongs in the suite, and it re-measures every day this task published.
 */
tasks.register<JavaExec>("publishRooms") {
    group = "content"
    description = "Curate and publish the daily Vaults rooms"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.hillelsht.smart.domain.play.vaults.PublishRoomsKt")
    args("--months", (project.findProperty("months") as String? ?: "4"))
    if (project.hasProperty("from")) args("--from", project.property("from") as String)
}
