/** Coordinates shared by `harc.publishing` and the `:ha-bom` platform. */
object HarcPublishing {
  /**
   * The Maven group every library publishes under. Publishing needs this namespace (or a parent,
   * such as `ee.schimke`) verified on the Central Portal for the account behind
   * `MAVEN_CENTRAL_USERNAME`.
   */
  const val GROUP = "ee.schimke.ha"

  const val URL = "https://github.com/yschimke/homeassistant-remotecompose"

  /**
   * The project paths that publish, in dependency order. `:ha-bom` reads this to write its
   * constraints, so a module cannot publish and be missing from the BOM; each listed module must
   * also apply `harc.publishing`, which `:ha-bom` checks.
   */
  val LIBRARIES =
    listOf(
      ":ha-model",
      ":ha-client",
      ":rc-mdi-icons",
      ":rc-material3-motion",
      ":rc-components",
      ":rc-player-ui",
      ":rc-converter",
      ":room-ui",
    )
}
