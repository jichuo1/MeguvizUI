plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.jichuo1.meguviz.samples.generated"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.samples.generated"; minSdk = 27; targetSdk = 37; versionCode = 1; versionName = "1.0.0"
        testInstrumentationRunner = "io.github.jichuo1.meguviz.samples.generated.GeneratedInstrumentation"
    }
    buildTypes { release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true; warningsAsErrors = true }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(project(":ui-widgets"))
    compileOnly(project(":ui-codegen-api"))
    ksp(project(":ui-codegen"))
}
dependencyLocking { lockAllConfigurations() }
