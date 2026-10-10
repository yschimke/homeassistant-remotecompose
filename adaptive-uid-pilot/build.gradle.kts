plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }

// This opt-in desktop specimen uses the native UID renderer's Material vocabulary.
// It does not change the Android app's dependencies or branded theme.
dependencies {
  implementation(libs.compose.multiplatform.ui.tooling.preview)
  implementation(compose.desktop.currentOs)
  implementation(libs.compose.multiplatform.material3)
  implementation(libs.compose.multiplatform.adaptive)
  implementation(libs.compose.multiplatform.adaptive.layout)
  implementation(libs.compose.multiplatform.adaptive.navigation)
  testImplementation(kotlin("test"))
  testImplementation(libs.compose.multiplatform.ui.test)
  testImplementation(libs.compose.multiplatform.ui.test.junit4)
}

tasks.register<JavaExec>("renderPilot") {
  group = "verification"
  description = "Render the independent app screen at every UID reference size and state."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass = "ee.schimke.adaptivepilot.RenderPilotKt"
  args(layout.buildDirectory.dir("pilot/previews").get().asFile.absolutePath)
  systemProperty("java.awt.headless", "true")
}
