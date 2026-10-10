plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.jvm)
  id("org.jetbrains.compose") version "1.12.1"
  alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }

// This opt-in desktop specimen uses the native UID renderer's Material vocabulary.
// It does not change the Android app's dependencies or branded theme.
dependencies {
  implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.12.1")
  implementation(compose.desktop.currentOs)
  implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
  implementation("org.jetbrains.compose.material3.adaptive:adaptive:1.3.0")
  implementation("org.jetbrains.compose.material3.adaptive:adaptive-layout:1.3.0")
  implementation("org.jetbrains.compose.material3.adaptive:adaptive-navigation:1.3.0")
  testImplementation(kotlin("test"))
  testImplementation("org.jetbrains.compose.ui:ui-test:1.12.1")
  testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.12.1")
}

tasks.register<JavaExec>("renderPilot") {
  group = "verification"
  description = "Render the independent app screen at every UID reference size and state."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass = "ee.schimke.adaptivepilot.RenderPilotKt"
  args(layout.buildDirectory.dir("pilot/previews").get().asFile.absolutePath)
  systemProperty("java.awt.headless", "true")
}
