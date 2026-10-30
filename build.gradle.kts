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
                            id.set("JE-Ramos")
                            name.set("JE Ramos (EveryTech)")
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

// Every jar (classes, sources, klib and metadata jars) carries the license and the notice, as
// Apache-2.0 section 4 asks of anyone who redistributes the code.
subprojects {
    // org.gradle.jvm.tasks.Jar is the base type: the Kotlin sources jars are of that type, and the
    // plain `Jar` in a build script (org.gradle.api.tasks.bundling.Jar) would miss them.
    tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
        metaInf {
            from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
        }
    }
}

// Kotlin 2.1's Wasm linker cannot run inside the Gradle daemon (it loses its runtime resources
// and fails with an NPE in writeCompilationResult), so only the link step runs out of process.
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>().configureEach {
        compilerExecutionStrategy.set(org.jetbrains.kotlin.gradle.tasks.KotlinCompilerExecutionStrategy.OUT_OF_PROCESS)
    }
}
