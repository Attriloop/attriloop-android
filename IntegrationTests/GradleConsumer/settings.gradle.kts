pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Prefer the AAR produced by `./gradlew publishToMavenLocal` so this
        // smoke test consumes the obfuscated release artifact, not sources.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "attriloop-android-consumer"
