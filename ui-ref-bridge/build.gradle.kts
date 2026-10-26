plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.jichuo1.meguviz.ui.ref"
    compileSdk = 37
    defaultConfig { minSdk = 27; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; warningsAsErrors = true }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    api(project(":ui-core"))
    api(libs.meguviz.ref.core)
    testImplementation(libs.junit)
    testImplementation(project(":ui-testkit"))
}
dependencyLocking { lockAllConfigurations() }
publishing {
    repositories { maven { name = "Candidate"; url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI() } }
    publications { register<MavenPublication>("release") {
        artifactId = "meguviz-ui-ref-bridge"
        afterEvaluate { from(components["release"]) }
        pom { name = "MeguvizUI Ref bridge"; description = "Optional dynamic View factories and UI-owned receiver leases using real MeguvizRef plans." }
    } }
}
