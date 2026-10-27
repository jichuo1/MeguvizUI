plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.jichuo1.meguviz.samples.p2benchmark"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.samples.p2benchmark"
        minSdk = 27; targetSdk = 37; versionCode = 1; versionName = "1.0.0"
        testInstrumentationRunner = "io.github.jichuo1.meguviz.samples.p2benchmark.P2PerformanceInstrumentation"
    }
    buildTypes {
        // Timed runs must not use a debuggable runtime; R8 is not applied because the
        // instrumentation APK links against the unobfuscated target classes.
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    testBuildType = "benchmark"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
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
