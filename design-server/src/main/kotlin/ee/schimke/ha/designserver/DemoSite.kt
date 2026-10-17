package ee.schimke.ha.designserver

import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

/** The WASM artifact lives beside the server, independently of its card library and editor. */
fun Route.demoSite(directory: File) {
  get("/demo") { call.respondRedirect("/demo/") }
  staticFiles("/demo", directory)
}
