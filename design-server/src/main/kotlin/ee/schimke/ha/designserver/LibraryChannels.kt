package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignCatalog
import ee.schimke.ha.design.DesignManifest
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a library is served: [channel] `main` is the library every app syncs, `pr-<n>` a pull
 * request's preview of it. Its client protocol lives at [api], the app's design server URL for the
 * channel; its browser pages at `/designs` with `?channel=` for any other channel.
 */
data class LibraryPaths(val channel: String) {
  val isMain: Boolean
    get() = channel == MAIN

  /** The client protocol's base: `/designs/v1`, or `/designs/v1/channels/<channel>`. */
  val api: String = if (channel == MAIN) API_BASE else "$API_BASE/channels/$channel"

  /** A browser page's path with this channel's query added to [query] (already encoded). */
  fun page(path: String, query: String = ""): String {
    val params = listOfNotNull(query.takeIf { it.isNotEmpty() }, channelParam())
    return if (params.isEmpty()) path else "$path?${params.joinToString("&")}"
  }

  /** A design's browser page. */
  fun design(id: String, query: String = ""): String = page("/designs/$id", query)

  /** The `channel=` query parameter for links that keep this channel, or null for main. */
  fun channelParam(): String? =
    if (isMain) null else "channel=${URLEncoder.encode(channel, Charsets.UTF_8)}"

  companion object {
    const val MAIN = "main"

    /** `main`, or a pull request's channel `pr-<number>`. */
    val CHANNEL = Regex("main|pr-[1-9][0-9]{0,8}")

    val Main = LibraryPaths(MAIN)
  }
}

/**
 * The libraries this server serves, one per channel ([LibraryPaths]), and how a new one is
 * published without redeploying the server. A design server is server-driven UI first: the image it
 * runs carries a library only as a seed, and what it serves is whatever was last published.
 *
 * With a [root] (a writable directory, `LIBRARY_STORE`), each channel is `root/<channel>/`:
 * - `main` starts as [seed] (the library baked into the image, or given on the command line) and is
 *   replaced by a newer seed only when the image's library was built after the one installed;
 * - [install] publishes a library to a channel: CI on each merge (`main`) and for each open pull
 *   request (`pr-<n>`), and live edits to the library's designs ([LibraryEditPublisher]);
 * - [delete] drops a pull request's channel when it closes.
 *
 * Without a [root] the server serves [seed] read-only as `main`, as a local run does.
 *
 * Every install is stamped by this server: a serial that only grows (the time, or one past the
 * channel's last), a [ttlSeconds] that bounds how long an app keeps the manifest before asking
 * again, and a fresh expiry. So a CI run that finishes late, or a seed built before a live edit,
 * can never hand an app a serial lower than one it already accepted.
 */
class LibraryChannels(
  val root: File?,
  seed: File,
  private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val checkIntervalMs: Long = 5_000,
  /** Without a [root], the holder already serving [seed], if there is one. */
  readOnlyMain: LibraryHolder? = null,
) {
  lateinit var main: LibraryHolder
    private set

  private val channels = ConcurrentHashMap<String, LibraryHolder>()

  /** Every installed library file, once, by content hash ([missing], [unpack]). */
  private val objects: File? = root?.let { File(it, OBJECTS) }

  /**
   * Hashes [missing] told a publisher it holds, and when: a publish that drops their last channel
   * mid-upload mustn't collect them from under the publisher that is leaving them out.
   */
  private val promised = ConcurrentHashMap<String, Long>()

  init {
    if (root == null) {
      main = readOnlyMain ?: LibraryHolder(seed, checkIntervalMs, clock)
    } else {
      root.mkdirs()
      seedMain(seed)
      main = LibraryHolder(dir(LibraryPaths.MAIN), checkIntervalMs, clock)
    }
  }

  /** Whether libraries can be published here: there is a writable [root]. */
  val publishable: Boolean
    get() = root != null

  /** The library served on [channel], or null when nothing was published there. */
  fun holder(channel: String): LibraryHolder? {
    if (channel == LibraryPaths.MAIN) return main
    if (root == null || !LibraryPaths.CHANNEL.matches(channel)) return null
    channels[channel]?.let {
      return it
    }
    val dir = dir(channel)
    if (!File(dir, DesignLibrary.MANIFEST).isFile) return null
    return runCatching { LibraryHolder(dir, checkIntervalMs, clock, LibraryPaths(channel)) }
      .getOrNull()
      ?.let { channels.putIfAbsent(channel, it) ?: it }
  }

  /** Every channel with a library, `main` first. */
  fun list(): List<ChannelInfo> {
    val names =
      listOf(LibraryPaths.MAIN) +
        root
          ?.listFiles { file -> file.isDirectory && LibraryPaths.CHANNEL.matches(file.name) }
          .orEmpty()
          .map { it.name }
          .filter { it != LibraryPaths.MAIN }
          .sorted()
    return names.mapNotNull { name ->
      val library = holder(name)?.current() ?: return@mapNotNull null
      ChannelInfo(
        channel = name,
        serial = library.manifest.serial,
        designs = library.designs.size,
        api = library.paths.api,
        published = readPublished(library.dir),
      )
    }
  }

  /**
   * Publishes the library in [staging] (as `:card-designs:generateCardDesigns` writes it) to
   * [channel], replacing what was there; [staging] is consumed. It is verified first
   * ([DesignLibrary.load]), so a broken library never replaces a working one. With [expectSerial],
   * it installs only if the channel still serves that serial: a library derived from what was
   * served (a live edit) never replaces one published since. Returns what the channel now serves.
   */
  @Synchronized
  fun install(
    channel: String,
    staging: File,
    origin: String,
    commit: String? = null,
    expectSerial: Long? = null,
    pullRequest: PullRequest? = null,
  ): ChannelInfo {
    val root = checkNotNull(root) { "this server has no library store" }
    require(LibraryPaths.CHANNEL.matches(channel)) { "not a channel: $channel" }
    try {
      // Verifies every blob against the manifest; throws naming the first problem.
      val built = DesignLibrary.load(staging)
      val target = dir(channel)
      val current = runCatching { DesignLibrary.load(target) }.getOrNull()
      if (expectSerial != null && current?.manifest?.serial != expectSerial) {
        throw StaleLibrary(
          "$channel moved on from serial $expectSerial to ${current?.manifest?.serial}"
        )
      }
      val now = clock()
      val serial = maxOf(now, (current?.manifest?.serial ?: 0) + 1)
      val manifest =
        built.manifest.copy(
          serial = serial,
          ttlSeconds = ttlSeconds,
          expiresAtEpochMs = now + VALID_FOR_MS,
        )
      // Replaced, never written through: an unpacked file may be a link into the object store.
      File(staging, DesignLibrary.MANIFEST)
        .rewrite(json.encodeToString(DesignManifest.serializer(), manifest))
      built.catalog?.let {
        File(staging, DesignCatalog.PATH)
          .rewrite(json.encodeToString(DesignCatalog.serializer(), it.copy(serial = serial)))
      }
      val published =
        Published(
          sourceGeneratedAtEpochMs = built.manifest.generatedAtEpochMs,
          publishedAtEpochMs = now,
          origin = origin,
          commit = commit,
          pullRequest = pullRequest,
        )
      File(staging, PUBLISHED).rewrite(json.encodeToString(Published.serializer(), published))
      // The manifest's time is how a holder sees a new library: always later than the last one,
      // even two installs within a file system's timestamp granularity.
      val previous = File(target, DesignLibrary.MANIFEST).lastModified()
      File(staging, DesignLibrary.MANIFEST).setLastModified(maxOf(now, previous + 1_000))
      remember(staging)
      swap(staging, target)
      collectGarbage()
      // While seeding there is no holder for main yet: it loads what was just installed.
      val library =
        if (channel == LibraryPaths.MAIN && !::main.isInitialized) DesignLibrary.load(target)
        else {
          val holder = holder(channel) ?: error("$channel didn't load after publishing")
          holder.reloadIfChanged()
          holder.current()
        }
      return ChannelInfo(
        channel,
        library.manifest.serial,
        library.designs.size,
        library.paths.api,
        published,
      )
    } finally {
      staging.deleteRecursively()
    }
  }

  /** Drops a pull request's channel. `main` can't be deleted. Returns whether it existed. */
  @Synchronized
  fun delete(channel: String): Boolean {
    require(channel != LibraryPaths.MAIN) { "main can't be deleted" }
    val root = root ?: return false
    if (!LibraryPaths.CHANNEL.matches(channel)) return false
    channels.remove(channel)
    val dir = dir(channel)
    if (!dir.exists()) return false
    return dir.deleteRecursively().also { collectGarbage() }
  }

  /**
   * A fresh directory under [root] to write or unpack a library into before [install]: on the same
   * file system as the channels, so installing it is a rename.
   */
  fun stagingDir(): File {
    val root = checkNotNull(root) { "this server has no library store" }
    return File(root, ".staging-${UUID.randomUUID()}").also { it.mkdirs() }
  }

  /**
   * The content hashes (SHA-256, hex) among [hashes] this server holds no file for: what a
   * publisher has to upload. Every file of every installed library is kept once, by content, so a
   * publish sends only what changed since anything was last published ([unpack]).
   */
  fun missing(hashes: Collection<String>): Set<String> {
    val missing = hashes.filterTo(LinkedHashSet()) { !SHA256.matches(it) || !objectFile(it).isFile }
    // What it said it holds, the publisher won't send: kept through its upload ([collectGarbage]).
    val now = clock()
    for (hash in hashes) if (hash !in missing) promised[hash] = now
    return missing
  }

  /**
   * Unpacks a library bundle into a [stagingDir]: a zip of library files (`manifest.json`,
   * `catalog.json`, `blobs/`, `previews/`, `sources/`) and, optionally, `files.json`, the whole
   * library's index (`{"files": {"<path>": "<sha256>"}}`). With an index the zip need only hold the
   * files [missing] named; the rest are taken from what this server already holds. Each file is
   * checked against its hash. Refuses anything that isn't a library file, any path that leaves the
   * directory, and more than [maxBytes] unpacked.
   */
  fun unpack(input: InputStream, maxBytes: Long = MAX_LIBRARY_BYTES): File {
    val staging = stagingDir()
    try {
      var total = 0L
      var entries = 0
      var index: Map<String, String>? = null
      ZipInputStream(input).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          if (entry.isDirectory) continue
          val name = entry.name.removePrefix("./")
          require(name == INDEX || LIBRARY_FILE.matches(name)) { "not a library file: $name" }
          require(++entries <= MAX_LIBRARY_FILES) { "more than $MAX_LIBRARY_FILES files" }
          val file = File(staging, name)
          file.parentFile.mkdirs()
          file.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              val read = zip.read(buffer)
              if (read < 0) break
              total += read
              require(total <= maxBytes) { "library larger than $maxBytes bytes" }
              out.write(buffer, 0, read)
            }
          }
        }
      }
      File(staging, INDEX)
        .takeIf { it.isFile }
        ?.let { indexFile ->
          index = json.decodeFromString(Index.serializer(), indexFile.readText()).files
          indexFile.delete()
        }
      index?.let { assemble(staging, it) }
      require(File(staging, DesignLibrary.MANIFEST).isFile) { "no ${DesignLibrary.MANIFEST}" }
      return staging
    } catch (e: Throwable) {
      staging.deleteRecursively()
      throw e
    }
  }

  /**
   * Completes [staging] (a [stagingDir]) to the library [files] indexes (path to SHA-256): each
   * file already there is checked against its hash, and the others are taken from what this server
   * holds ([missing] named the ones it doesn't). Anything the index doesn't name is refused.
   */
  fun assemble(staging: File, files: Map<String, String>) {
    require(files.size <= MAX_LIBRARY_FILES) { "more than $MAX_LIBRARY_FILES files" }
    for ((path, sha) in files) {
      require(LIBRARY_FILE.matches(path)) { "not a library file: $path" }
      require(SHA256.matches(sha)) { "not a SHA-256: $sha" }
      val file = File(staging, path)
      if (file.isFile) {
        require(sha256(file) == sha) { "$path doesn't match its hash" }
      } else {
        val stored = objectFile(sha)
        require(stored.isFile) { "$path wasn't uploaded and isn't held here" }
        file.parentFile.mkdirs()
        link(stored, file)
      }
    }
    // Nothing the index doesn't name: a stray upload would serve outside the library.
    staging
      .walkTopDown()
      .filter { it.isFile }
      .forEach { require(it.relativeTo(staging).path in files) { "${it.name} isn't indexed" } }
  }

  /**
   * [channel]'s library as a mirror copies it ([LibraryMirror]): each file by path with its
   * SHA-256, and when the library it was published from was built. Hashed once per installed
   * library. Null for a channel nothing was published to.
   */
  fun index(channel: String): LibraryIndex? {
    val dir = holder(channel)?.dir ?: return null
    val stamp = File(dir, DesignLibrary.MANIFEST).lastModified()
    indexes[channel]
      ?.takeIf { it.first == stamp }
      ?.let {
        return it.second
      }
    val files =
      dir
        .walkTopDown()
        .filter { it.isFile && LIBRARY_FILE.matches(it.relativeTo(dir).path) }
        .associate { it.relativeTo(dir).path to sha256(it) }
        .toSortedMap()
    val index = LibraryIndex(builtAt(dir) ?: return null, files)
    indexes[channel] = stamp to index
    return index
  }

  /** One file of [channel]'s library by its [path] in the library, or null. */
  fun libraryFile(channel: String, path: String): File? {
    if (!LIBRARY_FILE.matches(path)) return null
    return holder(channel)?.dir?.let { File(it, path) }?.takeIf { it.isFile }
  }

  /** When the library [channel] was last published from was built, or null without one. */
  fun builtAt(channel: String): Long? = holder(channel)?.dir?.let(::builtAt)

  private fun builtAt(dir: File): Long? =
    readPublished(dir)?.sourceGeneratedAtEpochMs
      ?: runCatching { DesignLibrary.load(dir).manifest.generatedAtEpochMs }.getOrNull()

  private val indexes = ConcurrentHashMap<String, Pair<Long, LibraryIndex>>()

  /** A library's files by path, with their SHA-256 ([index]). */
  @Serializable
  data class LibraryIndex(val generatedAtEpochMs: Long, val files: Map<String, String>)

  /** Keeps every file of [library] in the object store, so later publishes needn't send it. */
  private fun remember(library: File) {
    library
      .walkTopDown()
      // The manifest and catalog are stamped per install ([install]): no publish sends them as is.
      .filter {
        it.isFile &&
          it.name != PUBLISHED &&
          it.name != DesignLibrary.MANIFEST &&
          it.name != DesignCatalog.PATH
      }
      .forEach { file ->
        val stored = objectFile(sha256(file))
        if (!stored.isFile) {
          stored.parentFile.mkdirs()
          link(file, stored)
        }
      }
  }

  /**
   * Drops stored files no channel uses any more: those only the object store links to. Without hard
   * links (a file system that copies), nothing is dropped.
   */
  private fun collectGarbage() {
    val objects = objects ?: return
    val now = clock()
    promised.entries.removeIf { now - it.value > PROMISE_MS }
    objects
      .walkTopDown()
      .filter { it.isFile }
      .forEach { file ->
        // Promised to a publisher still uploading: its bundle leaves it out.
        if (promised.containsKey(file.name)) return@forEach
        val links = runCatching {
          Files.getAttribute(file.toPath(), "unix:nlink") as Int
        }
          .getOrNull()
        if (links == 1) file.delete()
      }
  }

  private fun objectFile(sha: String): File = File(checkNotNull(objects), "${sha.take(2)}/$sha")

  /** [to] as a hard link to [from], or a copy where the file system has none. */
  private fun link(from: File, to: File) {
    runCatching { Files.createLink(to.toPath(), from.toPath()) }
      .getOrElse { from.copyTo(to, overwrite = true) }
  }

  @Serializable private class Index(val files: Map<String, String>)

  /** Replaces this file with [text] as a new file, leaving any other link to the old one alone. */
  private fun File.rewrite(text: String) {
    delete()
    writeText(text)
  }

  /**
   * Installs [seed] as `main` when there is no `main` yet, or when [seed] was built after the
   * library `main` was last published from: a new image's library replaces an older publish, and
   * never a newer one.
   */
  private fun seedMain(seed: File) {
    val seedManifest = runCatching { DesignLibrary.load(seed).manifest }.getOrNull() ?: return
    val installedFrom = builtAt(dir(LibraryPaths.MAIN))
    if (installedFrom != null && installedFrom >= seedManifest.generatedAtEpochMs) return
    val staging = stagingDir()
    seed.copyRecursively(staging, overwrite = true)
    File(staging, PUBLISHED).delete()
    install(LibraryPaths.MAIN, staging, origin = "image")
  }

  private fun dir(channel: String): File = File(checkNotNull(root), channel)

  /**
   * Replaces [target] with [staging]: the old directory moves aside, the new one takes its name,
   * then the old one is deleted. A holder that loaded the old library keeps its blobs in memory.
   */
  private fun swap(staging: File, target: File) {
    val old = File(target.parentFile, ".${target.name}.old-${UUID.randomUUID()}")
    if (target.exists()) check(target.renameTo(old)) { "couldn't move $target aside" }
    check(staging.renameTo(target)) { "couldn't move $staging to $target" }
    old.deleteRecursively()
  }

  /** What a channel serves. */
  @Serializable
  data class ChannelInfo(
    val channel: String,
    val serial: Long,
    val designs: Int,
    /** The channel's client protocol base: the app's design server URL, after the host. */
    val api: String,
    val published: Published? = null,
  )

  /** Where a channel's library came from, kept beside it as `published.json`. */
  @Serializable
  data class Published(
    /** When the library was generated (its manifest's `generatedAtEpochMs` as built). */
    val sourceGeneratedAtEpochMs: Long,
    val publishedAtEpochMs: Long,
    /** `image`, `ci` or `edit:<cardType>`. */
    val origin: String,
    /** The commit CI built it from, when it says. */
    val commit: String? = null,
    /** The pull request a `pr-<n>` channel previews, as CI described it. */
    val pullRequest: PullRequest? = null,
  )

  /** An in-flight pull request a channel previews: what the browser shows to tell them apart. */
  @Serializable
  data class PullRequest(
    val number: Int,
    val title: String? = null,
    val url: String? = null,
    val branch: String? = null,
    val author: String? = null,
  )

  /** A library derived from a channel that has since moved on ([install]'s `expectSerial`). */
  class StaleLibrary(message: String) : Exception(message)

  companion object {
    /** How long an app keeps a manifest before asking again: an hour, revalidated by ETag. */
    const val DEFAULT_TTL_SECONDS: Long = 60 * 60

    /** How long a published manifest stays valid for an app that can't reach the server. */
    const val VALID_FOR_MS: Long = 14L * 24 * 60 * 60 * 1000

    /**
     * The largest library bundle accepted, unpacked: a whole library with its previews is about
     * half of it. A publish against a library this server holds sends only what changed.
     */
    const val MAX_LIBRARY_BYTES: Long = 1024L * 1024 * 1024

    private const val MAX_LIBRARY_FILES = 50_000

    /** Where a channel keeps where its library came from. */
    const val PUBLISHED = "published.json"

    /** How long an object [missing] reported as held is kept for its publisher's upload. */
    const val PROMISE_MS: Long = 30 * 60 * 1000

    /** A bundle's index of the whole library ([unpack]). */
    const val INDEX = "files.json"

    /** The object store's directory under the root: not a channel name. */
    private const val OBJECTS = ".objects"

    private val SHA256 = Regex("[0-9a-f]{64}")

    fun sha256(file: File): String {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          digest.update(buffer, 0, read)
        }
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The files a library holds; nothing else is unpacked. */
    private val LIBRARY_FILE =
      Regex(
        "manifest\\.json|catalog\\.json|blobs/[0-9a-f]{64}\\.rc|" +
          "previews/[0-9a-f]{16}-(example|template)\\.png|sources/[A-Za-z0-9_.-]+\\.uid"
      )

    private val json = Json {
      prettyPrint = true
      ignoreUnknownKeys = true
    }

    fun readPublished(dir: File): Published? =
      File(dir, PUBLISHED)
        .takeIf { it.isFile }
        ?.let {
          runCatching { json.decodeFromString(Published.serializer(), it.readText()) }.getOrNull()
        }
  }
}
