plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}
android {
    namespace = "io.github.jichuo1.meguviz.samples.p2consumer"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.samples.p2consumer"
        minSdk = 27; targetSdk = 37; versionCode = 1; versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true; warningsAsErrors = true }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(libs.widgets)
    implementation(libs.plan)
    implementation(libs.recycler)
    implementation(libs.text)
    implementation(libs.layouts)
    implementation(libs.prewarm)
    compileOnly(libs.annotations)
    ksp(libs.processor)
}
dependencyLocking { lockAllConfigurations() }
tasks.matching { it.name.startsWith("lint") }.configureEach {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
