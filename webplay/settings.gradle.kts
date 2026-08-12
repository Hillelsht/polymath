// Standalone build: compiles the Vaults' pure-Kotlin movement to JavaScript so the game can be
// played in a browser, on this machine, without an Android SDK or a device.
//
// The same arrangement enginetests/ uses — a separate build pointing at the app's real sources —
// so there is one implementation of the physics and nothing to keep in sync.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "smart-webplay"
