package ee.schimke.terrazzo.shared

import java.io.File

/**
 * Where the Android app keeps agent capabilities: a directory in its private storage, set by the
 * app at start ([AgentGrantFiles.use]). Until then (previews, tests) they live in memory only.
 */
object AgentGrantFiles {
  @Volatile private var dir: File? = null

  fun use(directory: File) {
    dir = directory
  }

  internal fun file(key: String): File? = dir?.let {
    File(it, key.filter { c -> c.isLetterOrDigit() || c == '.' } + ".json")
  }
}

internal actual fun readAgentGrants(key: String): String? = runCatching {
  AgentGrantFiles.file(key)?.takeIf { it.isFile }?.readText()
}
  .getOrNull()

internal actual fun writeAgentGrants(key: String, value: String?) {
  val file = AgentGrantFiles.file(key) ?: return
  runCatching {
    if (value == null) file.delete()
    else {
      file.parentFile?.mkdirs()
      val temp = File(file.parentFile, file.name + ".tmp")
      temp.writeText(value)
      temp.renameTo(file)
    }
  }
}
