package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.client.design.DesignRepository
import ee.schimke.ha.client.design.DesignServerConfig
import ee.schimke.ha.client.design.InMemoryDesignStore
import ee.schimke.ha.client.design.RefreshResult
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The app's own design client against the server: what a phone pointed at it does. */
class ClientCompatibilityTest {
  @Test
  fun theAppsDesignRepositoryRefreshesAndResolvesFromTheServer() = testApplication {
    val template = javaClass.getResource("/tile-light.rc")!!.readBytes()
    val key =
      DesignKey(
        cardType = "tile",
        surface = DesignSurface.MobileApp,
        rcApiLevel = 8,
        shape = "domain=light",
        theme = "baked:Material3:light",
      )
    val dir = Files.createTempDirectory("designs").toFile()
    val now = System.currentTimeMillis()
    DesignPublisher.publish(listOf(BuiltDesign(key, 1, template)), serial = 1, nowEpochMs = now)
      .files
      .forEach { (path, bytes) ->
        File(dir, path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
      }
    application { designServer(LibraryHolder(dir)) }

    val repository =
      DesignRepository(
        // As the app's design server setting: the server's client protocol base.
        DesignServerConfig(baseUrl = "http://localhost$API_BASE", allowUnsigned = true),
        InMemoryDesignStore(),
        client,
        clock = { now },
      )
    assertEquals(RefreshResult.Updated, repository.refresh(force = true))
    assertContentEquals(template, assertNotNull(repository.resolve(key)).bytes)
    // The second refresh revalidates the manifest by ETag.
    assertEquals(RefreshResult.NotModified, repository.refresh(force = true))
  }
}
