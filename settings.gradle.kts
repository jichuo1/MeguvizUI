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
fun p2Enabled(property: String) = providers.gradleProperty("enableP2").orNull == "true" ||
    providers.gradleProperty(property).orNull == "true"
if (p2Enabled("enablePlan")) include(":ui-plan", ":samples:plan-consumer")
if (p2Enabled("enableRecycler")) include(":ui-recycler")
if (p2Enabled("enableText")) include(":ui-text")
if (p2Enabled("enableLayouts")) include(":ui-layouts")
if (p2Enabled("enablePrewarm")) include(":ui-prewarm")
if (p2Enabled("enableCodegen")) {
    include(":ui-codegen-api", ":ui-codegen", ":samples:generated-consumer")
}
// The same-input performance harness needs every P2 module, so it follows the aggregate switch only.
if (providers.gradleProperty("enableP2").orNull == "true") include(":samples:p2-benchmark", ":samples:p2-matrix")
