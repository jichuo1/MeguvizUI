pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { name = "PinnedMeguvizCandidates"; url = uri("build/upstream-repo") } }
            filter {
                includeModule("io.github.jichuo1.meguviz", "meguviz-android-runtime")
                includeModule("io.github.jichuo1.meguviz", "meguviz-android-window")
            }
        }
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.jichuo1.LumenCoacervationEngine") }
        }
    }
}

rootProject.name = "MeguvizUI"
include(":ui-core", ":ui-widgets", ":ui-testkit", ":samples:standalone", ":samples:xml-interop")

if (providers.gradleProperty("enableLumen").orNull == "true") include(":ui-lumen-bridge")
if (providers.gradleProperty("enableRef").orNull == "true") include(":ui-ref-bridge")
if (providers.gradleProperty("enableAndroid").orNull == "true") include(":ui-android-bridge")
