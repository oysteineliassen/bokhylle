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
        google()
        mavenCentral()
    }
}
rootProject.name = "Bokhylle"
// Ren Kotlin-logikk (sidetall, sortering, tekstopprydding) som kan testes uten Android.
include(":core")
// BOKHYLLE_KUN_CORE=1 bygger bare core, f.eks. på en maskin uten Android SDK.
if (System.getenv("BOKHYLLE_KUN_CORE") == null) include(":app")
