plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.jichuo1.meguviz.samples.p2matrix"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.samples.p2matrix"
        minSdk = 27; targetSdk = 37; versionCode = 1; versionName = "1.0.0"
        testInstrumentationRunner = "io.github.jichuo1.meguviz.samples.p2matrix.P2MatrixInstrumentation"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // Locale is overridden in-app for the text matrix, so every language must ship in the base APK.
    bundle { language { enableSplit = false } }
    lint { abortOnError = true; warningsAsErrors = true }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(project(":ui-widgets"))
    implementation(project(":ui-plan"))
    implementation(project(":ui-recycler"))
    implementation(project(":ui-text"))
    implementation(project(":ui-layouts"))
    implementation(project(":ui-prewarm"))
    compileOnly(project(":ui-codegen-api"))
    ksp(project(":ui-codegen"))
}
dependencyLocking { lockAllConfigurations() }
