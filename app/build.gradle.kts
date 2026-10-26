plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "badfox.logic.mylte"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "badfox.logic.mylte"
        minSdk = 26
        targetSdk = 37
        versionCode = 13
        versionName = "0.1.12"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":neoui"))
    implementation(project(":icons"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.maplibre)
}
