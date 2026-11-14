package ee.schimke.terrazzo.containers

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs `adb` against the one connected device, or the one `ANDROID_SERIAL` names (adb reads that
 * itself). Uses `$ANDROID_HOME/platform-tools/adb` when there is one, else `adb` on the `PATH`.
 */
class Adb(private val log: (String) -> Unit = ::println) {

  private val executable: String =
    listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
      .map { File(it, "platform-tools/adb") }
      .firstOrNull { it.canExecute() }
      ?.absolutePath ?: "adb"

  /** Runs `adb [args]` and returns its combined output; fails when adb exits non-zero. */
  fun run(vararg args: String, timeoutMinutes: Long = 5): String {
    log("$ adb ${args.joinToString(" ")}")
    val process = ProcessBuilder(listOf(executable) + args).redirectErrorStream(true).start()
    val output = StringBuilder()
    val reader = Thread {
      process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
    }
    reader.start()
    if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
      process.destroyForcibly()
      error("adb ${args.joinToString(" ")} timed out after $timeoutMinutes minutes:\n$output")
    }
    reader.join()
    val text = output.toString()
    if (text.isNotBlank()) log(text.trimEnd())
    check(process.exitValue() == 0) {
      "adb ${args.joinToString(" ")} exited ${process.exitValue()}:\n$text"
    }
    return text
  }

  fun shell(command: String, timeoutMinutes: Long = 5): String =
    run("shell", command, timeoutMinutes = timeoutMinutes)

  /** Whether exactly one device is connected and booted, so the test has somewhere to run. */
  fun deviceReady(): Boolean = runCatching {
    run("get-state").trim() == "device" && shell("getprop sys.boot_completed").trim() == "1"
  }
    .getOrDefault(false)
}
