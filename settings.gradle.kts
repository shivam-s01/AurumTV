pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor / NewValve (native YouTube stream resolution,
        // same stack as the phone app) are published on JitPack only.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AurumTV"
include(":app")
