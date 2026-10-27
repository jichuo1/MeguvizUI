plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}
android {
    namespace = "io.github.jichuo1.meguviz.ui.text"
    compileSdk = 37
    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "io.github.jichuo1.meguviz.ui.text.TextInstrumentation"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; warningsAsErrors = true }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies { api(project(":ui-core")); testImplementation(libs.junit) }
dependencyLocking { lockAllConfigurations() }
publishing {
    repositories { maven { name = "Candidate"; url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI() } }
    publications { register<MavenPublication>("release") {
        artifactId = "meguviz-ui-text"
        afterEvaluate { from(components["release"]) }
        pom { name = "MeguvizUI Text"; description = "Optional non-blocking text precomputation." }
    } }
}
