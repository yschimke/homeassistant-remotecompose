plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(libs.versions.java.get().toInt()) }

dependencies {
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit)
}

tasks.test {
  // Rendered PNGs come from the compose-preview CLI's `composePreviewRender`
  // task, which the CLI's auto-inject init script materialises on the
  // `:previews` module — run `compose-preview show` locally (CI does this
  // in the `test` job before `./gradlew test`) so the renders exist before
  // the pixel-diff suites run. The task isn't visible to a plain
  // `./gradlew` invocation, so we wire `dependsOn` only when it exists;
  // the pixel-diff tests hard-fail when the renders are missing.
  rootProject.findProject(":previews")?.tasks?.findByName("composePreviewRender")?.let {
    dependsOn(it)
  }

  systemProperty(
    "ha.rendered.dir",
    rootProject.file("previews/build/compose-previews/renders").absolutePath,
  )
  systemProperty("ha.references.dir", rootProject.file("references").absolutePath)
}
