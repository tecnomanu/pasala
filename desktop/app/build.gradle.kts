import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose)
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":shared"))
  implementation(compose.desktop.currentOs)
  implementation(compose.foundation)
  implementation(compose.components.resources)
  implementation(libs.kotlinx.coroutines.swing)
  implementation(libs.zxing.core)
  implementation(libs.sqlite.jdbc)
  implementation(libs.jna.platform)
}

compose.resources {
  packageOfResClass = "com.tecnomanu.pasala.desktop.res"
  publicResClass = false
}

compose.desktop {
  application {
    // A top-level `Pasala` class: Linux names the windows' WM_CLASS after it, so docks and taskbars say "Pasala".
    mainClass = "Pasala"
    jvmArgs += listOf("-Dfile.encoding=UTF-8")
    nativeDistributions {
      targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
      packageName = "Pasala"
      packageVersion = "1.0.0"
      // Windows shows this as the app's name (firewall prompt, Task Manager).
      description = "Pasala"
      vendor = "tecnomanu"
      copyright = "MIT"
      licenseFile.set(project.file("../../LICENSE"))
      modules("java.sql", "jdk.unsupported", "java.naming", "jdk.crypto.ec")
      windows {
        iconFile.set(project.file("../icons/pasala.ico"))
        menuGroup = "Pasala"
        perUserInstall = true
        shortcut = true
        menu = true
        // Fixed so a newer .msi upgrades the installed one instead of adding a second copy.
        upgradeUuid = "6f0c2f1e-5a5b-4f43-9d55-6a3c7f1d2b40"
      }
      linux {
        iconFile.set(project.file("../icons/pasala.png"))
        packageName = "pasala"
        debMaintainer = "tecnomanu@users.noreply.github.com"
        menuGroup = "Network;FileTransfer;"
        shortcut = true
      }
    }
  }
}
