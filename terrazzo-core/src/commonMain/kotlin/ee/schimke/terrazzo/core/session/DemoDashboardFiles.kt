package ee.schimke.terrazzo.core.session

/**
 * The bundled demo dashboard file at [path] (`/dashboards/<slug>/<file>.json`), or null when there
 * is none. On Android and the JVM they are classpath resources (`src/androidMain/resources`); on
 * Wasm, with no classpath, a source the build generates from the same files.
 */
internal expect fun demoDashboardFile(path: String): String?
