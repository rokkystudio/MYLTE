plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("badfox.logic.sharedapp:flags")).using(project(":flags"))
            substitute(module("com.rokkystudio.sharedapp:geo")).using(project(":geo"))
        }
    }
}