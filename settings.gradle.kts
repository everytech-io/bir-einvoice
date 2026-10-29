pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
rootProject.name = "bir-einvoice-root"
include(":bir-einvoice", ":bir-einvoice-mock")
