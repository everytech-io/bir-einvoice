plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    `maven-publish`
}

description = "A local stand-in for the BIR EIS API, for testing EIS clients without sandbox credentials"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

java {
    withSourcesJar()
}

dependencies {
    api(project(":bir-einvoice"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.everytech.bireinvoice.mock.MockEisMain")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.withType<Test>().configureEach {
    // The Node client interop test runs only when this points at the Node e-invoice folder.
    listOf("BIR_EINVOICE_NODE_DIR").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
