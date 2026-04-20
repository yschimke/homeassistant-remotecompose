plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(libs.versions.java.get().toInt()) }

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()

    // Our rendered PNGs come from `:previews:renderAllPreviews`. Wire it so
    // `./gradlew :integration:test` is a single command.
    dependsOn(":previews:renderAllPreviews")

    systemProperty(
        "ha.rendered.dir",
        rootProject.file("previews/build/compose-previews/renders").absolutePath,
    )
    systemProperty(
        "ha.references.dir",
        rootProject.file("references").absolutePath,
    )
}
