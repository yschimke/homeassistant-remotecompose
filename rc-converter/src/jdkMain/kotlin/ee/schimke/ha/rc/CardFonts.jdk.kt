package ee.schimke.ha.rc

internal actual fun classpathFontFile(path: String): ByteArray? =
  HaFontLibrary::class.java.classLoader?.getResourceAsStream(path)?.use { it.readBytes() }
