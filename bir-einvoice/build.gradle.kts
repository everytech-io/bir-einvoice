plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

description = "Kotlin Multiplatform client for the Philippine BIR EIS e-invoice API (CAS JSON, crypto, transport)"

kotlin {
    jvmToolchain(21)
    explicitApi()

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    // The Node interop test runs only when these point at a running Node mock.
    listOf("BIR_EINVOICE_NODE_MOCK_CREDS").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
