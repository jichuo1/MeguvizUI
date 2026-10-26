plugins { alias(libs.plugins.android.application) }

android {
    namespace = "io.github.jichuo1.meguviz.samples.standalone"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.samples.standalone"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "io.github.jichuo1.meguviz.samples.standalone.ContractInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; warningsAsErrors = true }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies { implementation(project(":ui-widgets")) }
dependencyLocking { lockAllConfigurations() }
