plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.jichuo1.meguviz.ui.prewarm"
    compileSdk = 37
    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "io.github.jichuo1.meguviz.ui.prewarm.PrewarmInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; warningsAsErrors = true }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    api(project(":ui-core"))
    testImplementation(libs.junit)
    testImplementation(project(":ui-testkit"))
}
dependencyLocking { lockAllConfigurations() }

publishing {
    repositories { maven { name = "Candidate"; url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI() } }
    publications { register<MavenPublication>("release") {
        artifactId = "meguviz-ui-prewarm"
        afterEvaluate { from(components["release"]) }
        pom { name = "MeguvizUI Prewarm"; description = "Optional bounded, owner-thread prewarming." }
    } }
}
