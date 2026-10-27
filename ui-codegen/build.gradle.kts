plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }
dependencies { implementation(libs.symbol.processing.api) }
dependencyLocking { lockAllConfigurations() }
publishing {
    repositories { maven { name = "Candidate"; url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI() } }
    publications { register<MavenPublication>("release") {
        artifactId = "meguviz-ui-codegen"
        from(components["java"])
        pom { name = "MeguvizUI Codegen"; description = "Optional KSP processor for explicit View declarations." }
    } }
}
