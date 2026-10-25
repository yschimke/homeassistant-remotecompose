package ee.schimke.terrazzo.dashboard

import android.content.Context
import ee.schimke.ha.client.design.RefreshResult
import ee.schimke.ha.rc.components.HaUberTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Installing and removing card styles. The app starts with two, [BUILT_IN]: Classic (the cards' own
 * look in the app's palette, Material 3 by default) and Home Assistant. Every other style is listed
 * with a sample, and installed on its own: installing downloads its designs from the design server
 * for the palette the dashboard draws in, and only then can the dashboard or a card be switched to
 * it. The design sync keeps an installed style's designs current ([CardDesignChoice.cardStyles]).
 */
object CardStyles {
  /** The styles every install starts with, which need no installing. */
  val BUILT_IN: Set<String> = setOf(HaUberTheme.Classic.id, HaUberTheme.HomeAssistant.id)

  /** Whether [styleId] can be chosen now: a built-in style, or one in [installed]. */
  fun isInstalled(styleId: String, installed: Set<String>): Boolean =
    styleId in BUILT_IN || styleId in installed

  /** How an [install] ended. */
  enum class Installed {
    /** Its designs are on disk and the style is installed. */
    Yes,
    /** No design server, or it didn't answer with the style's designs: nothing was installed. */
    Failed,
  }

  /**
   * Downloads [styleId]'s designs for every theme [choice] draws it in, then records it installed
   * ([CardStyleStore]). It runs the design sync's own refresh with the style added, one refresh at
   * a time with the background sync, and installs the style only once all of its designs are
   * cached, so an install that fails half way leaves the style uninstalled.
   */
  suspend fun install(context: Context, choice: CardDesignChoice, styleId: String): Installed {
    if (styleId in BUILT_IN) return Installed.Yes
    if (!choice.enabled) return Installed.Failed
    val withStyle = choice.copy(cardStyles = choice.cardStyles + styleId)
    val repository = CardDesignSync.networkRepository(context, withStyle)
    val themes = withStyle.themesOf(HaUberTheme.byId(styleId)).toSet()
    val complete =
      withContext(Dispatchers.IO) {
        CardDesignSync.refreshLock.withLock {
          val result = repository.refresh()
          // Every design the style draws, not just some: a refresh that fetched only part of it
          // still reports success.
          result != RefreshResult.Rejected &&
            result != RefreshResult.Failed &&
            repository.isCompleteIn(themes)
        }
      }
    if (!complete) return Installed.Failed
    CardStyleStore.of(context).add(styleId)
    CardDesignSync.stored()
    return Installed.Yes
  }

  /**
   * Removes [styleId]: the sync stops fetching its designs. Only a style nothing uses can be
   * removed ([inUse]); a built-in one stays. Its cached designs stay on disk (the design cache is
   * never pruned), so installing it again is quick.
   */
  suspend fun uninstall(context: Context, styleId: String) {
    if (styleId in BUILT_IN) return
    CardStyleStore.of(context).remove(styleId)
    CardDesignSync.stored()
  }

  /** The styles in use: the dashboard's and every card's or card type's own. */
  fun inUse(dashboardStyle: String, overrideStyles: Set<String>): Set<String> =
    overrideStyles + dashboardStyle
}

/**
 * The card styles installed on this device beyond [CardStyles.BUILT_IN]:
 * `files/terrazzo/designs/card-styles.json`. Writes replace the file atomically.
 *
 * An install from before card styles were installable has no file yet; [seed] then records the
 * styles it already uses as installed, so updating the app takes no style away.
 */
class CardStyleStore internal constructor(private val file: File) {
  private val _installed = MutableStateFlow(read().orEmpty())

  /** One update and its write at a time, so the file always ends up with the newest set. */
  private val writes = Mutex()

  /** The installed styles' ids, built-in styles aside. */
  val installed: StateFlow<Set<String>> = _installed.asStateFlow()

  /** Records [styleId] as installed, once its designs are on disk ([CardStyles.install]). */
  suspend fun add(styleId: String) = update { it + styleId }

  /** Forgets [styleId]: the sync stops fetching its designs. */
  suspend fun remove(styleId: String) = update { it - styleId }

  /**
   * On the first run with installable styles (no file yet), records [inUse] as installed: the
   * styles this install already draws with. Later runs leave the file as it is.
   */
  suspend fun seed(inUse: Set<String>) {
    writes.withLock {
      if (withContext(Dispatchers.IO) { file.exists() }) return
      _installed.update { it + (inUse - CardStyles.BUILT_IN) }
      write(_installed.value)
    }
  }

  private suspend fun update(change: (Set<String>) -> Set<String>) {
    writes.withLock {
      _installed.update(change)
      write(_installed.value)
    }
  }

  private suspend fun write(styles: Set<String>) {
    val text = json.encodeToString(Installed.serializer(), Installed(styles.sorted()))
    withContext(Dispatchers.IO) {
      val dir = requireNotNull(file.parentFile).apply { mkdirs() }
      val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
      try {
        tmp.writeText(text)
        java.nio.file.Files.move(
          tmp.toPath(),
          file.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
      } finally {
        tmp.delete()
      }
    }
  }

  private fun read(): Set<String>? = runCatching {
    file
      .takeIf { it.isFile }
      ?.readText()
      ?.let { json.decodeFromString(Installed.serializer(), it).installed.toSet() }
  }
    .getOrNull()

  @Serializable private data class Installed(val installed: List<String> = emptyList())

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var instance: CardStyleStore? = null

    /** The app's one store. */
    fun of(context: Context): CardStyleStore =
      instance
        ?: synchronized(this) {
          instance
            ?: CardStyleStore(
                File(context.applicationContext.filesDir, "terrazzo/designs/card-styles.json")
              )
              .also { instance = it }
        }
  }
}
