pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("../../build/repo") } }
            filter { includeGroup("io.github.jichuo1.meguviz") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "MeguvizP2Consumer"
include(":app")
