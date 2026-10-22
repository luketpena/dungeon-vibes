// AGP 9 compiles Kotlin itself ("built-in Kotlin") and ships with an older Kotlin Gradle plugin.
// Putting the newer KGP/KSP on the build classpath here is the documented way to use a newer Kotlin.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
