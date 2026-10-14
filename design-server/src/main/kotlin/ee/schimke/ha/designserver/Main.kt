package ee.schimke.ha.designserver

import io.ktor.client.HttpClient
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
 * - UI Builder, optional: `UI_BUILDER_BINARY` runs the bundled one, `UI_BUILDER_URL` +
 *   `UI_BUILDER_TOKEN` use another (see [UiBuilderConfig.fromEnvironment]). With it, a browser can
 *   seed UI Builder designs from card schemas.
 * - Who may: with `GITHUB_CLIENT_ID` (+ `GITHUB_CLIENT_SECRET`, `PUBLIC_URL`, `SESSION_SECRET`,
 *   optional `GITHUB_USERS`), anyone who signs in with GitHub, each design shared with its designer
 *   ([GithubAuth]); without it, a browser that opened the edit link the server prints.
 * - The front page's links: `DESIGNER_URL`, `CATALOG_URL`, `STORE_URL`
 *   ([SiteConfig.fromEnvironment]).
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
  // Redirects are answers here (UI Builder's share route, GitHub's), never something to follow.
  val http = HttpClient(io.ktor.client.engine.cio.CIO) { followRedirects = false }
  val github = GithubAuth.fromEnvironment(System.getenv(), http)
  val uiBuilder =
    UiBuilderConfig.fromEnvironment()?.let { (config, process) ->
      process?.start()
      println(
        "UI Builder: ${config.browserUrl}" +
          (if (process != null) " (bundled)" else "") +
          if (github != null) " - editing: sign in with GitHub"
          else
            " - edit link (keep it private, it unlocks editing): " +
              "http://localhost:$port/edit?token=${config.token}"
      )
      UiBuilderIntegration(config, UiBuilderClient(config, http))
    }
  embeddedServer(CIO, port = port, host = host) {
      install(CallLogging)
      designServer(holder, uiBuilder, github, SiteConfig.fromEnvironment())
    }
    .start(wait = true)
}
