// Standalone build: compiles the app's pure-Kotlin domain and content code on the JVM so the
// learning engine and the curriculum can be tested without the Android SDK.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "smart-enginetests"
