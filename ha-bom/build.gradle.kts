plugins {
  `java-platform`
  id("harc.base-conventions")
  id("harc.publishing")
}

// One coordinate that pins every published library at one version:
//
//     implementation(platform("ee.schimke.ha:ha-bom:<version>"))
//     implementation("ee.schimke.ha:ha-model")
//     implementation("ee.schimke.ha:rc-converter")
//
// The constraints come from `HarcPublishing.LIBRARIES`, the list the release publishes, so a
// library cannot be released and missing from the BOM. Kotlin Multiplatform modules are
// constrained at their root coordinate; Gradle module metadata resolves the per-target variants.

dependencies { constraints { HarcPublishing.LIBRARIES.forEach { api(project(it)) } } }

mavenPublishing {
  pom {
    name.set("Home Assistant Remote Compose BOM")
    description.set("Version constraints for every homeassistant-remotecompose library")
  }
}
