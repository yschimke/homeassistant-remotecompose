package ee.schimke.ha.designserver

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import java.io.File

/**
 * Runs the card design server.
 *
 * - `DESIGN_LIBRARY` (or the first argument): the library directory, as
 *   `:card-designs:generateCardDesigns` writes it. Default `card-designs`.
 * - `PORT`: default 8080. `HOST`: default `0.0.0.0`.
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull() ?: System.getenv("DESIGN_LIBRARY") ?: "card-designs")
  val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
  val host = System.getenv("HOST") ?: "0.0.0.0"
  val holder = LibraryHolder(dir)
  val library = holder.current()
  println(
    "card design server: ${library.designs.size} designs (serial ${library.manifest.serial}) " +
      "from ${dir.absolutePath} on http://$host:$port - app URL: <host>$API_BASE"
  )
  embeddedServer(CIO, port = port, host = host) {
      install(CallLogging)
      designServer(holder)
    }
    .start(wait = true)
}
