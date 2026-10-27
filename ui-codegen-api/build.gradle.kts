plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }
dependencyLocking { lockAllConfigurations() }
publishing {
    repositories { maven { name = "Candidate"; url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI() } }
    publications { register<MavenPublication>("release") {
        artifactId = "meguviz-ui-codegen-api"
        from(components["java"])
        pom { name = "MeguvizUI Codegen annotations"; description = "Optional source-only declarations for MeguvizUI generation." }
    } }
}
