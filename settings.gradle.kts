pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android and its spake2-android transitive live on JitPack.
        // The filter keeps this repository from resolving anything that is
        // not published by MuntashirAkon; the spake2 group is a sub-group
        // (com.github.MuntashirAkon.spake2-java), so the regex covers both.
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\.MuntashirAkon(\\..*)?")
            }
        }
    }
}

rootProject.name = "IDUPI"
include(":app")
include(":shellhelper")
 