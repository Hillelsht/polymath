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
