pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    google()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Pasala"

include(":app")
project(":app").projectDir = file("app")
// Protocol core shared with android/ (docs/engineering/DECISIONS.md#d-010).
include(":shared")
project(":shared").projectDir = file("../kotlin/shared")
