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
    alias(libs.plugins.kotlin.jvm) apply false
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
val selectedP2Profiles = listOf(
    "plan" to "enablePlan", "recycler" to "enableRecycler", "text" to "enableText",
    "layouts" to "enableLayouts", "prewarm" to "enablePrewarm", "codegen" to "enableCodegen",
).filter { (_, property) -> providers.gradleProperty("enableP2").orNull == "true" ||
    providers.gradleProperty(property).orNull == "true" }.map { it.first }

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

val p2Start = tasks.register<Exec>("startP2Verification") {
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/p2_gates.py", "start", "--root", rootDir.absolutePath,
        "--profiles", selectedP2Profiles.joinToString(","))
}

val p2Api = tasks.register<Exec>("verifyP2Api") {
    group = "verification"
    dependsOn(p2Start, "publishCandidate")
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/p2_api.py", "--root", rootDir.absolutePath)
}

val p2DeviceProtocol = tasks.register<Exec>("verifyP2DeviceProtocol") {
    group = "verification"
    dependsOn(p2Start)
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/test_p2_device.py")
}

val p2PerformanceProtocol = tasks.register<Exec>("verifyP2PerformanceProtocol") {
    group = "verification"
    dependsOn(p2Start)
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/test_p2_performance.py")
}

val p2MatrixProtocol = tasks.register<Exec>("verifyP2MatrixProtocol") {
    group = "verification"
    dependsOn(p2Start)
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/test_p2_matrix.py")
}

subprojects {
    // Metadata Maven Central requires on every published POM; no personal contact data is published.
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url.set("https://github.com/jichuo1/MeguvizUI")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers { developer { id.set("jichuo1"); name.set("jichuo1"); url.set("https://github.com/jichuo1") } }
                    scm {
                        url.set("https://github.com/jichuo1/MeguvizUI")
                        connection.set("scm:git:https://github.com/jichuo1/MeguvizUI.git")
                        developerConnection.set("scm:git:ssh://git@github.com/jichuo1/MeguvizUI.git")
                    }
                }
            }
        }
    }
    // When verifyP2 is present, every project task must follow the source preflight,
    // including shared core compilation and publication, not just tests and lint.
    tasks.configureEach { mustRunAfter(p2Start) }
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

tasks.register<Exec>("verifyP2") {
    group = "verification"
    description = "Verifies all six opt-in P2 profiles on the host; device and performance evidence remain separate."
    dependsOn(p2Start, "publishCandidate", p2Api, p2DeviceProtocol)
    if (selectedP2Profiles.size == 6) {
        dependsOn(p2PerformanceProtocol, ":samples:p2-benchmark:assembleBenchmark",
            ":samples:p2-benchmark:assembleBenchmarkAndroidTest", ":samples:p2-benchmark:lintBenchmark")
        dependsOn(p2MatrixProtocol, ":samples:p2-matrix:assembleDebug",
            ":samples:p2-matrix:assembleDebugAndroidTest", ":samples:p2-matrix:lintDebug")
    }
    for (name in selectedP2Profiles.filter { it != "codegen" }) {
        dependsOn(":ui-$name:assembleRelease", ":ui-$name:testDebugUnitTest", ":ui-$name:lintDebug")
    }
    for (name in listOf("recycler", "text", "layouts", "prewarm")) if (name in selectedP2Profiles) {
        dependsOn(":ui-$name:assembleDebugAndroidTest")
    }
    if ("plan" in selectedP2Profiles) {
        dependsOn(":samples:plan-consumer:assembleDebug", ":samples:plan-consumer:assembleDebugAndroidTest",
            ":samples:plan-consumer:lintDebug")
    }
    if ("codegen" in selectedP2Profiles) {
        dependsOn(":ui-codegen:jar", ":ui-codegen-api:jar", ":samples:generated-consumer:assembleDebug",
            ":samples:generated-consumer:assembleRelease", ":samples:generated-consumer:assembleDebugAndroidTest",
            ":samples:generated-consumer:lintDebug")
    }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/p2_gates.py", "finish", "--root", rootDir.absolutePath,
        "--profiles", selectedP2Profiles.joinToString(","))
}

tasks.register<Exec>("verifyP2Performance") {
    group = "verification"
    description = "Measures same-input A/B samples for all six P2 profiles on one explicitly authorized physical device."
    dependsOn("verifyP2")
    val serial = providers.gradleProperty("p2PerformanceSerial")
    val allowInstall = providers.gradleProperty("allowDeviceInstall")
    val sdk = providers.environmentVariable("ANDROID_HOME").orElse(providers.provider {
        java.util.Properties().apply { rootDir.resolve("local.properties").takeIf { it.isFile }?.reader()?.use(::load) }
            .getProperty("sdk.dir") ?: error("Set ANDROID_HOME or sdk.dir")
    })
    doFirst {
        check(allowInstall.orNull == "true") { "Pass -PallowDeviceInstall=true to install the benchmark packages" }
        check(serial.isPresent) { "Pass -Pp2PerformanceSerial=<adb serial>" }
    }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/p2_performance.py", "--root", rootDir.absolutePath,
        "--adb", sdk.map { "$it/platform-tools/adb" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "" }.get(), "--serial", serial.getOrElse(""), "--allow-install")
}

tasks.register<Exec>("verifyP2Matrix") {
    group = "verification"
    description = "Runs the P2 behavior matrix on one explicitly authorized device; manual TalkBack listening stays separate."
    dependsOn("verifyP2")
    val serial = providers.gradleProperty("p2MatrixSerial")
    val allowInstall = providers.gradleProperty("allowDeviceInstall")
    val sdk = providers.environmentVariable("ANDROID_HOME").orElse(providers.provider {
        java.util.Properties().apply { rootDir.resolve("local.properties").takeIf { it.isFile }?.reader()?.use(::load) }
            .getProperty("sdk.dir") ?: error("Set ANDROID_HOME or sdk.dir")
    })
    doFirst {
        check(allowInstall.orNull == "true") { "Pass -PallowDeviceInstall=true to install the matrix packages" }
        check(serial.isPresent) { "Pass -Pp2MatrixSerial=<adb serial>" }
    }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "build-logic/verification/p2_matrix.py", "--root", rootDir.absolutePath,
        "--adb", sdk.map { "$it/platform-tools/adb" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "" }.get(),
        "--serial", serial.getOrElse(""), "--allow-install")
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
        "build-logic/verification/gates.py", "release", "--root", rootDir.absolutePath,
        "--profiles", selectedBuildProfiles, "--p2-profiles", selectedP2Profiles.joinToString(","))
    if (selectedP2Profiles.isNotEmpty()) dependsOn("verifyP2")
}

tasks.register("publishCandidate") {
    group = "publishing"
    dependsOn(":ui-core:publishReleasePublicationToCandidateRepository",
        ":ui-widgets:publishReleasePublicationToCandidateRepository")
    if (selectedBuildProfiles.contains("lumen")) dependsOn(":ui-lumen-bridge:publishReleasePublicationToCandidateRepository")
    for (profile in listOf("ref", "android")) if (profile in selectedBuildProfileNames) {
        dependsOn(":ui-$profile-bridge:publishReleasePublicationToCandidateRepository")
    }
    for (profile in selectedP2Profiles.filter { it != "codegen" }) {
        dependsOn(":ui-$profile:publishReleasePublicationToCandidateRepository")
    }
    if ("codegen" in selectedP2Profiles) {
        dependsOn(":ui-codegen:publishReleasePublicationToCandidateRepository",
            ":ui-codegen-api:publishReleasePublicationToCandidateRepository")
    }
}
