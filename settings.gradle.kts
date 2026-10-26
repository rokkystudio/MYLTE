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
    }
}

rootProject.name = "MYLTE"
include(":app")
include(":flags")
include(":neoui")
include(":icons")

project(":flags").projectDir = file("../SHARED-APP/flags")
project(":neoui").projectDir = file("../SHARED-APP/neoui")
project(":icons").projectDir = file("../SHARED-APP/icons")