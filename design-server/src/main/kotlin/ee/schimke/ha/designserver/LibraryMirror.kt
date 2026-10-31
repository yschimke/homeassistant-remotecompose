package ee.schimke.ha.designserver

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Keeps `main` a copy of another design server's published library ([upstream], e.g.
 * `https://ha.coo.ee`): a private server (the Home Assistant add-on) serves the same library as the
 * public one, with its own people's designs beside it.
 *
 * Each [sync] reads the upstream's index ([LibraryChannels.index], at
 * `/api/library/channels/main/files.json`) and installs it when it was built later than the library
 * `main` holds, as a new image's library replaces an older one. Only the files this server doesn't
 * hold are downloaded, each checked against its hash.
 */
class LibraryMirror(
  private val channels: LibraryChannels,
  upstream: String,
  private val http: HttpClient,
) {
  private val base = "${upstream.trimEnd('/')}$LIBRARY_CHANNELS_PATH/${LibraryPaths.MAIN}"

  /** Whether a newer library was installed. */
  suspend fun sync(): Boolean {
    val reply = http.get("$base/$INDEX_PATH")
    check(reply.status.isSuccess()) { "the upstream's index answered ${reply.status}" }
    val index = json.decodeFromString(LibraryChannels.LibraryIndex.serializer(), reply.bodyAsText())
    val local = channels.builtAt(LibraryPaths.MAIN)
    if (local != null && local >= index.generatedAtEpochMs) return false
    val missing = channels.missing(index.files.values)
    val staging = channels.stagingDir()
    try {
      for ((path, sha) in index.files) {
        if (sha !in missing) continue
        val file = File(staging, path)
        file.parentFile.mkdirs()
        download("$base/$FILES_PATH/$path", file)
      }
      withContext(Dispatchers.IO) {
        // Checks each downloaded file against the index and links in the ones held here.
        channels.assemble(staging, index.files)
        channels.install(LibraryPaths.MAIN, staging, origin = ORIGIN)
      }
    } catch (e: Throwable) {
      staging.deleteRecursively()
      throw e
    }
    return true
  }

  /** [sync]s now and then every [intervalMs]; a failure waits for the next one. */
  fun start(scope: CoroutineScope, intervalMs: Long): Job = scope.launch {
    while (isActive) {
      runCatching { sync() }
        .onSuccess { if (it) println("library mirror: installed a newer library from $base") }
        .onFailure { System.err.println("library mirror: $base: ${it.message}") }
      delay(intervalMs)
    }
  }

  private suspend fun download(url: String, to: File) {
    val reply = http.get(url) { header(HttpHeaders.AcceptEncoding, "identity") }
    check(reply.status.isSuccess()) { "$url answered ${reply.status}" }
    withContext(Dispatchers.IO) {
      reply.bodyAsChannel().toInputStream().use { input ->
        to.outputStream().use { input.copyTo(it) }
      }
    }
  }

  companion object {
    /** Under a channel's [LIBRARY_CHANNELS_PATH] entry: its index, and its files by path. */
    const val INDEX_PATH = "files.json"
    const val FILES_PATH = "files"

    /** How a mirrored library's origin reads in `published.json`. */
    const val ORIGIN = "mirror"

    private val json = Json { ignoreUnknownKeys = true }
  }
}
