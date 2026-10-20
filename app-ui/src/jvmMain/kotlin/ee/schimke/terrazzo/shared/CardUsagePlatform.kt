package ee.schimke.terrazzo.shared

import ee.schimke.ha.client.design.UsageLedger
import ee.schimke.ha.client.design.UsageStore
import ee.schimke.ha.client.design.UsageSurfaces
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** The desktop app keeps its usage ledger and setting under `~/.terrazzo/usage/`. */
internal actual fun platformCardUsage(): PlatformCardUsage? {
  val dir = File(System.getProperty("user.home"), ".terrazzo/usage")
  val setting = File(dir, "sharing")
  return PlatformCardUsage(
    store = FileUsageLedgerStore(File(dir, "ledger.json")),
    surface = UsageSurfaces.DESKTOP,
    platform = "desktop",
    sendUserAgentHeader = true,
    readSharing = { runCatching { setting.readText().trim() }.getOrNull() },
    writeSharing = { name ->
      runCatching {
        dir.mkdirs()
        setting.writeText(name)
      }
    },
  )
}

/** The [UsageLedger] in one JSON file, written atomically. */
private class FileUsageLedgerStore(private val file: File) : UsageStore {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun read(): UsageLedger? =
    withContext(Dispatchers.IO) {
      if (!file.isFile) return@withContext null
      runCatching { json.decodeFromString(UsageLedger.serializer(), file.readText()) }.getOrNull()
    }

  override suspend fun write(ledger: UsageLedger) =
    withContext(Dispatchers.IO) {
      val dir = requireNotNull(file.parentFile).apply { mkdirs() }
      val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
      try {
        tmp.writeText(json.encodeToString(UsageLedger.serializer(), ledger))
        Files.move(
          tmp.toPath(),
          file.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      } finally {
        tmp.delete()
      }
      Unit
    }

  override suspend fun delete() =
    withContext(Dispatchers.IO) {
      file.delete()
      Unit
    }
}
