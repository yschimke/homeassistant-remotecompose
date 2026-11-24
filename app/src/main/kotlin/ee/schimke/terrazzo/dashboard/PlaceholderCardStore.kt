package ee.schimke.terrazzo.dashboard

import android.content.Context
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.client.dashboard.PlaceholderCard
import ee.schimke.terrazzo.core.di.AppScope
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Private local briefs, isolated by HA server and dashboard; never merged into Lovelace config. */
@SingleIn(AppScope::class)
class PlaceholderCardStore internal constructor(private val root: File) {
  @Inject
  constructor(
    context: Context,
  ) : this(File(context.applicationContext.filesDir, "terrazzo/card-briefs"))

  private val writes = Mutex()
  private val codec = ListSerializer(PlaceholderCard.serializer())

  suspend fun read(server: String, dashboard: String?): List<PlaceholderCard> = writes.withLock {
    withContext(Dispatchers.IO) {
      val file = file(server, dashboard)
      if (!file.exists()) emptyList() else Json.decodeFromString(codec, file.readText())
    }
  }

  suspend fun write(server: String, dashboard: String?, cards: List<PlaceholderCard>) {
    writes.withLock {
      withContext(Dispatchers.IO) {
        root.mkdirs()
        val target = file(server, dashboard)
        val temp = File.createTempFile("briefs-", ".tmp", root)
        try {
          temp.writeText(Json.encodeToString(codec, cards))
          Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )
        } finally {
          temp.delete()
        }
      }
    }
  }

  private fun file(server: String, dashboard: String?): File {
    val scope =
      Json.encodeToString(
        ListSerializer(String.serializer()),
        listOf(server.trimEnd('/'), dashboard.orEmpty()),
      )
    val digest =
      MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") {
        "%02x".format(it)
      }
    return File(root, "$digest.json")
  }
}
