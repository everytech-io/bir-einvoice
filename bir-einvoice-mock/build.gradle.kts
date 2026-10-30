import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

description = "A local stand-in for the BIR EIS API, for testing EIS clients without sandbox credentials"

kotlin {
    jvmToolchain(21)
    explicitApi()

    jvm {
        // JavaUsageTest compiles the README's Java example.
        withJava()
    }

    // MockEis (the processing without the HTTP and crypto layers) also runs in a browser.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":bir-einvoice"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The standalone runner: MOCK_EIS_PORT=8787 ./gradlew :bir-einvoice-mock:run --args="creds.json"
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the mock EIS on its own and writes matching credentials"
    val jvm = kotlin.jvm()
    val main = jvm.compilations.getByName("main")
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("io.everytech.bireinvoice.mock.MockEisMain")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    standardInput = System.`in`
}

tasks.withType<Test>().configureEach {
    // The optional client interop test runs only when this names the folder of an independent
    // implementation of the protocol, which is not published.
    listOf("BIR_EINVOICE_NODE_DIR").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
