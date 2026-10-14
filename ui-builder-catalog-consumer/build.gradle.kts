plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation("ee.schimke.composeai:compose-preview-ui-builder-runtime:3.113.0")
  testImplementation(kotlin("test"))
}

val catalogDirectory =
  project(":ui-builder-catalog").layout.buildDirectory.dir("catalog/testDebugUnitTest")

tasks.named<Test>("test") {
  dependsOn(":ui-builder-catalog:exportTypedCatalog")
  inputs.dir(catalogDirectory)
  systemProperty("typedCatalogDirectory", catalogDirectory.get().asFile.absolutePath)
  systemProperty("wasmSupported", "false")
}
