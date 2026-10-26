plugins { alias(libs.plugins.android.library) }

android {
    namespace = "io.github.jichuo1.meguviz.ui.testkit"
    compileSdk = 37
    defaultConfig { minSdk = 27 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies { api(project(":ui-core")); testImplementation(libs.junit) }
dependencyLocking { lockAllConfigurations() }
