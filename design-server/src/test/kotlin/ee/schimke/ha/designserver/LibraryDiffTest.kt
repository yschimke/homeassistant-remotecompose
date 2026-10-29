package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignCatalog
import ee.schimke.ha.design.DesignCatalogEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignPreviews
import ee.schimke.ha.design.DesignSurface
import io.ktor.http.parametersOf
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * A pull request's library against the published one: each design is new, unchanged, changed only
 * in its document, or changed in its picture, and published designs it drops are removed. The
 * browser filters by that and shows the published picture beside a changed one.
 */
class LibraryDiffTest {
  private val tile = javaClass.getResource("/tile-light.rc")!!.readBytes()
  private val gauge = javaClass.getResource("/gauge-unit.rc")!!.readBytes()
  private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
  private val otherPng = png + 1

  private fun key(domain: String) =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=$domain",
      theme = "baked:Material3:light",
    )

  private val same = key("light")
  private val document = key("switch")
  private val picture = key("fan")
  private val removed = key("cover")
  private val added = key("lock")

  /** A library of [designs] (key to its document and example picture), served on [paths]. */
  private fun library(
    designs: Map<DesignKey, Pair<ByteArray, ByteArray>>,
    paths: LibraryPaths,
  ): DesignLibrary {
    val dir = Files.createTempDirectory("designs").toFile()
    val output =
      DesignPublisher.publish(
        designs.map { (key, design) -> BuiltDesign(key, 1, design.first) },
        serial = 1,
        nowEpochMs = 1_000,
      )
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    val entries = designs.map { (key, design) ->
      val id = DesignLibrary.idOf(key)
      val example = DesignCatalog.previewPath(id, "example")
      File(dir, example).also { it.parentFile.mkdirs() }.writeBytes(design.second)
      DesignCatalogEntry(
        id = id,
        sha256 = output.manifest.designs.first { it.key == key }.sha256,
        key = key,
        previews = DesignPreviews(example = example),
      )
    }
    File(dir, DesignCatalog.PATH)
      .writeText(Json.encodeToString(DesignCatalog.serializer(), DesignCatalog(1, 1_000, entries)))
    return DesignLibrary.load(dir, paths)
  }

  private val base =
    library(
      mapOf(
        same to (tile to png),
        document to (tile to png),
        picture to (tile to png),
        removed to (tile to png),
      ),
      LibraryPaths.Main,
    )

  private val pr =
    library(
      mapOf(
        same to (tile to png),
        document to (gauge to png),
        picture to (tile to otherPng),
        added to (tile to png),
      ),
      LibraryPaths("pr-42"),
    )

  private val diff = LibraryDiff.of(pr, base)

  private fun change(key: DesignKey) = diff.of(pr.designFor(key)!!)

  @Test
  fun classifiesEachDesignAgainstThePublishedOne() {
    assertEquals(LibraryDiff.Change.Unchanged, change(same))
    assertEquals(LibraryDiff.Change.Document, change(document))
    assertEquals(LibraryDiff.Change.Picture, change(picture))
    assertEquals(LibraryDiff.Change.Added, change(added))
    assertEquals(listOf(removed), diff.removed.map { it.key })
  }

  private fun filter(vararg params: Pair<String, String>) =
    BrowserFilter.from(
      parametersOf(params.associate { (k, v) -> k to listOf(v) }),
      pr,
      defaults = false,
    )

  @Test
  fun filtersByChange() {
    fun keys(f: BrowserFilter) = f.apply(pr, diff).map { it.key }.toSet()
    assertEquals(setOf(document, picture, added), keys(filter("changes" to "changed")))
    assertEquals(setOf(picture), keys(filter("changes" to "picture")))
    assertEquals(setOf(document), keys(filter("changes" to "document")))
    assertEquals(setOf(added), keys(filter("changes" to "added")))
    assertEquals(emptySet(), keys(filter("changes" to "removed")))
    assertEquals(listOf(removed), filter("changes" to "removed").removed(diff).map { it.key })
    assertEquals(4, filter().apply(pr, diff).size)
  }

  @Test
  fun theBrowserSummarisesAndComparesChanges() {
    val page = Pages.browser(pr, filter("changes" to "changed", "compare" to "1"), diff = diff)
    assertTrue("1 pictures changed" in page, page)
    assertTrue("1 documents changed" in page)
    assertTrue("1 removed" in page)
    assertTrue("1 unchanged" in page)
    // A changed design shows the published picture beside its own; an unchanged one isn't shown.
    val published =
      "${LibraryPaths.Main.api}/${DesignCatalog.previewPath(DesignLibrary.idOf(picture), "example")}"
    assertTrue(published in page, "the published picture is beside the changed one")
    assertFalse(DesignLibrary.idOf(same) in page, "unchanged designs are filtered out")
    // A document-only change has the same picture: nothing to compare.
    val samePicture =
      "${LibraryPaths.Main.api}/${DesignCatalog.previewPath(DesignLibrary.idOf(document), "example")}"
    assertFalse(samePicture in page, "a document-only change shows one picture")
    assertTrue("<h2 id=\"removed\">" in page)
  }

  @Test
  fun mainHasNoChangeFilter() {
    val page = Pages.browser(base, BrowserFilter.from(parametersOf(), base, defaults = false))
    assertFalse("name=\"changes\"" in page)
  }
}
