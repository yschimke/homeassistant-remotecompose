package ee.schimke.terrazzo.shared

import androidx.compose.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Composable
internal actual fun rememberCatalogCache(): CatalogCache = remember {
  object : CatalogCache {
    private val dir = File(System.getProperty("user.home"), ".cache/terrazzo/catalog-v1")

    override fun read(key: String): String? = runCatching {
      File(dir, key).takeIf { it.isFile }?.readText()
    }
      .getOrNull()

    override fun write(key: String, value: String) {
      dir.mkdirs()
      val target = File(dir, key)
      val temporary = File.createTempFile("catalog-", ".tmp", dir)
      try {
        temporary.writeText(value)
        Files.move(
          temporary.toPath(),
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } finally {
        temporary.delete()
      }
    }
  }
}
