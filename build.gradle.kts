import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "studio.converter"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    compilerOptions {
        // The oldest supported IDE (2026.2) bundles the Kotlin 2.4 standard library.
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No upper bound: the plugin uses only stable platform APIs, and JetBrains Marketplace
            // checks every new IDE release against it.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            providers.gradleProperty("verifyIdes").get().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
                val (code, ideVersion) = entry.split(':', limit = 2)
                create(IntelliJPlatformType.fromCode(code), ideVersion)
            }
        }
    }

    // Used only by the release workflow (.github/workflows/release.yml), from repository secrets.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

// The plugin's version as a resource, for its X-Converter-Studio-Client header.
val versionResource = tasks.register("versionResource") {
    val out = layout.buildDirectory.dir("generated/versionResource")
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    outputs.dir(out)
    doLast {
        val file = out.get().file("studio/converter/ide/version.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(pluginVersion)
    }
}
sourceSets.main { resources.srcDir(versionResource) }

// End-to-end tests against a local Converter Studio, kept outside the published sources.
if (file("e2e/kotlin").isDirectory) {
    sourceSets.test { kotlin.srcDir("e2e/kotlin") }
}

tasks.test {
    // The end-to-end tests read their API address, key and samples from these.
    listOf("CS_E2E_API_URL", "CS_E2E_API_KEY", "CS_E2E_SAMPLES").forEach { name ->
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}
