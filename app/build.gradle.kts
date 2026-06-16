plugins {
    alias(libs.plugins.android.application)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

android {
    namespace = "fuck.andes"
    compileSdk = 36

    defaultConfig {
        applicationId = "fuck.andes"
        minSdk = 26
        targetSdk = 36
        versionCode = 136
        versionName = "1.3.6"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}
