import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Classpath
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    // The classpath anchor selects KGP 2.4.21; Android modules use AGP's built-in Kotlin.
    alias(libs.plugins.kotlin.android) apply false
}

allprojects {
    group = "io.github.jichuo1.meguviz"
    version = providers.gradleProperty("meguviz.version").get()
}

val selectedBuildProfileNames = buildList {
    add("core")
    if (providers.gradleProperty("enableLumen").orNull == "true") add("lumen")
    if (providers.gradleProperty("enableRef").orNull == "true") add("ref")
    if (providers.gradleProperty("enableAndroid").orNull == "true") add("android")
}
val selectedBuildProfiles = selectedBuildProfileNames.joinToString(",")

abstract class WriteRuntimeDependencies : DefaultTask() {
    @get:Input abstract val components: ListProperty<String>
    @get:OutputFile abstract val report: RegularFileProperty
    @TaskAction fun write() {
        report.get().asFile.apply { parentFile.mkdirs(); writeText(components.get().joinToString("\n", postfix = "\n")) }
    }
}

abstract class WriteRuntimeArtifacts : DefaultTask() {
    @get:Classpath abstract val binaries: ConfigurableFileCollection
    @get:OutputFile abstract val report: RegularFileProperty
    @TaskAction fun write() {
        val lines = binaries.files.filter { it.isFile }.sortedBy { it.name }.map { file ->
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            "${file.name}\t$sha\t${file.absolutePath}"
        }
        report.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n", postfix = "\n")) }
    }
}

abstract class WriteCompileFixtureClasspath : DefaultTask() {
    @get:Classpath abstract val binaries: ConfigurableFileCollection
    @get:OutputFile abstract val report: RegularFileProperty
    @TaskAction fun write() {
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(binaries.files.sortedBy { it.absolutePath }.joinToString("\n", postfix = "\n") { it.absolutePath })
        }
    }
}

val hostStart = tasks.register<Exec>("startHostVerification") {
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/gates.py", "start-host", "--root", rootDir.absolutePath, "--profiles", selectedBuildProfiles)
}

val compileFixtures = tasks.register<Exec>("verifyCompileFixtures") {
    group = "verification"
    dependsOn("publishCandidate")
    mustRunAfter(hostStart)
    for (profile in listOf("lumen", "ref", "android")) if (profile in selectedBuildProfileNames) {
        dependsOn(":ui-$profile-bridge:writeCompileFixtureClasspath")
    }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "integration/compile-fixtures/verify.py", "--profiles", selectedBuildProfiles)
}

val gateContracts = tasks.register<Exec>("verifyGateContracts") {
    group = "verification"
    mustRunAfter(hostStart)
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/test_gates.py")
}

subprojects {
    plugins.withId("com.android.library") {
        tasks.register<WriteRuntimeDependencies>("writeRuntimeDependencies") {
            components.set(configurations.named("releaseRuntimeClasspath").map { configuration ->
                configuration.incoming.resolutionResult.allComponents.map { it.id.displayName }.sorted()
            })
            report.set(layout.buildDirectory.file("reports/runtime-dependencies.txt"))
        }
        tasks.register<WriteRuntimeArtifacts>("writeRuntimeArtifacts") {
            binaries.from(configurations.named("releaseRuntimeClasspath").map { configuration ->
                configuration.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }.files
            })
            report.set(layout.buildDirectory.file("reports/runtime-artifacts.tsv"))
        }
        tasks.register<WriteCompileFixtureClasspath>("writeCompileFixtureClasspath") {
            // UI project classes are supplied by the published candidate AARs in compile fixtures.
            binaries.from(configurations.named("releaseCompileClasspath").map { configuration ->
                configuration.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }.files
            })
            report.set(layout.buildDirectory.file("reports/compile-fixture-classpath.txt"))
        }
    }
    tasks.withType<Test>().configureEach {
        mustRunAfter(hostStart)
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    tasks.matching { it.name.startsWith("lint") }.configureEach {
        mustRunAfter(hostStart)
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}

tasks.register<Exec>("verifyHost") {
    group = "verification"
    description = "Runs host compilation, contracts, lint and boundary checks with a fresh receipt."
    dependsOn(hostStart, compileFixtures, gateContracts, ":ui-core:assembleRelease", ":ui-core:testDebugUnitTest", ":ui-core:lintDebug",
        ":ui-core:writeRuntimeDependencies", ":ui-widgets:assembleRelease", ":ui-widgets:lintDebug",
        ":samples:standalone:assembleDebug", ":samples:standalone:assembleRelease", ":samples:standalone:lintDebug",
        ":samples:xml-interop:assembleDebug", ":samples:xml-interop:assembleRelease", ":samples:xml-interop:lintDebug")
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/gates.py", "host", "--root", rootDir.absolutePath, "--profiles", selectedBuildProfiles)
    if (selectedBuildProfiles.contains("lumen")) dependsOn(":ui-lumen-bridge:assembleRelease", ":ui-lumen-bridge:lintDebug")
    for (profile in listOf("ref", "android")) if (profile in selectedBuildProfileNames) {
        dependsOn(":ui-$profile-bridge:assembleRelease", ":ui-$profile-bridge:lintDebug",
            ":ui-$profile-bridge:testDebugUnitTest", ":ui-$profile-bridge:writeRuntimeArtifacts")
    }
}

val deviceStart = tasks.register<Exec>("startDeviceVerification") {
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/gates.py", "start-device", "--root", rootDir.absolutePath, "--profiles", selectedBuildProfiles)
}
val deviceReceipt = tasks.register<Exec>("recordDeviceVerification") {
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/gates.py", "device", "--root", rootDir.absolutePath, "--profiles", selectedBuildProfiles)
}
project(":samples:standalone") {
    plugins.withId("com.android.application") {
        tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
            dependsOn(deviceStart)
            finalizedBy(deviceReceipt)
        }
    }
}
tasks.register("verifyDevice") {
    group = "verification"
    description = "Runs declared device checks; missing devices and zero tests fail closed."
    dependsOn(":samples:standalone:connectedDebugAndroidTest")
}

tasks.register<Exec>("verifyReleaseCandidate") {
    group = "verification"
    description = "Checks current source, artifact and profile bound receipts; does not claim skipped gates passed."
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/gates.py", "release", "--root", rootDir.absolutePath, "--profiles", selectedBuildProfiles)
}

tasks.register("publishCandidate") {
    group = "publishing"
    dependsOn(":ui-core:publishReleasePublicationToCandidateRepository",
        ":ui-widgets:publishReleasePublicationToCandidateRepository")
    if (selectedBuildProfiles.contains("lumen")) dependsOn(":ui-lumen-bridge:publishReleasePublicationToCandidateRepository")
    for (profile in listOf("ref", "android")) if (profile in selectedBuildProfileNames) {
        dependsOn(":ui-$profile-bridge:publishReleasePublicationToCandidateRepository")
    }
}
