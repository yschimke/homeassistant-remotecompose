package ee.schimke.terrazzo.core.session

internal actual fun demoDashboardFile(path: String): String? =
  DemoData::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
