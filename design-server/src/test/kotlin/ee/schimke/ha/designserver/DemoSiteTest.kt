package ee.schimke.ha.designserver

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoSiteTest {
  @Test
  fun demoServesWasmWithoutReplacingOtherRoutes() = testApplication {
    val directory = Files.createTempDirectory("wasm-demo").toFile()
    try {
      directory.resolve("index.html").writeText("<html>WASM demo</html>")
      directory.resolve("terrazzo.wasm").writeBytes(byteArrayOf(0, 97, 115, 109))
      application { routing { demoSite(directory) } }
      assertTrue(client.get("/demo/").bodyAsText().contains("WASM demo"))
      val wasm = client.get("/demo/terrazzo.wasm")
      assertEquals(HttpStatusCode.OK, wasm.status)
      assertTrue(wasm.headers["Content-Type"].orEmpty().startsWith("application/wasm"))
      assertEquals(HttpStatusCode.NotFound, client.get("/demo/missing.js").status)
      assertEquals(HttpStatusCode.NotFound, client.get("/designs").status)
    } finally {
      directory.deleteRecursively()
    }
  }
}
