plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rokkystudio.mylte"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rokkystudio.mylte"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.1.5"
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
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
}
