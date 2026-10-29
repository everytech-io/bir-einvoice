plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// JitPack builds a tag with JITPACK=true and passes the coordinates it serves through GROUP,
// ARTIFACT and VERSION. Everywhere else the library is io.everytech:*:0.1.0.
val onJitPack = System.getenv("JITPACK") == "true"

allprojects {
    group = if (onJitPack) "${System.getenv("GROUP")}.${System.getenv("ARTIFACT")}" else "io.everytech"
    version = if (onJitPack) System.getenv("VERSION") ?: "0.1.0" else "0.1.0"
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set(provider { project.description })
                    url.set("https://github.com/everytech-io/bir-einvoice")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("everytech")
                            name.set("EveryTech")
                            url.set("https://everytech.io")
                        }
                    }
                    scm {
                        url.set("https://github.com/everytech-io/bir-einvoice")
                        connection.set("scm:git:https://github.com/everytech-io/bir-einvoice.git")
                        developerConnection.set("scm:git:ssh://git@github.com/everytech-io/bir-einvoice.git")
                    }
                }
            }
        }
    }
}
