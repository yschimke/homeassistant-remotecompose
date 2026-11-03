package ee.schimke.terrazzo.shared

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where the desktop app keeps agent capabilities: `~/.terrazzo/agent-grants`, readable by this user
 * alone, set by the app at start ([use]). Until then (tests, previews) they live in memory only.
 */
object DesktopAgentGrants {
  @Volatile private var dir: File? = null

  fun use(directory: File = File(System.getProperty("user.home"), ".terrazzo/agent-grants")) {
    dir = directory
  }

  internal fun file(key: String): File? = dir?.let {
    File(it, key.filter { c -> c.isLetterOrDigit() || c == '.' } + ".json")
  }
}

internal actual fun readAgentGrants(key: String): String? = runCatching {
  DesktopAgentGrants.file(key)?.takeIf { it.isFile }?.readText()
}
  .getOrNull()

internal actual fun writeAgentGrants(key: String, value: String?) {
  val file = DesktopAgentGrants.file(key) ?: return
  runCatching {
    if (value == null) {
      file.delete()
      return
    }
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, file.name + ".tmp")
    temp.writeText(value)
    runCatching {
      Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------"))
    }
    temp.renameTo(file)
  }
}
