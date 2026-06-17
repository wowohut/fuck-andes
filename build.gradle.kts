buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 内置 KGP 2.2.10，但 miuix 0.9.2 与 Compose Multiplatform 1.11.1 需要 Kotlin 2.4.0，
        // 这里用 buildscript classpath 强制提升 KGP 版本以覆盖内置版本。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
