package ee.schimke.ha.designserver

import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import java.io.File

/**
 * Runs the card design server.
 *
 * - `DESIGN_LIBRARY` (or the first argument): the library directory, as
 *   `:card-designs:generateCardDesigns` writes it. Default `card-designs`. With `LIBRARY_STORE` it
 *   is only the seed of `main` ([LibraryChannels]).
 * - `LIBRARY_STORE`: a writable directory the server keeps its published libraries in, one per
 *   channel (`main`, `pr-<n>`), so a library is published without redeploying the server. Without
 *   it the server serves `DESIGN_LIBRARY` read-only.
 * - `LIBRARY_PUBLISH_TOKEN`: the bearer token CI publishes libraries with (`PUT
 *   /api/library/channels/<channel>`); without it nobody can.
 * - `LIBRARY_UPSTREAM`: another design server (`https://ha.coo.ee`) whose published library `main`
 *   follows ([LibraryMirror]), checked every `LIBRARY_UPSTREAM_POLL_SECONDS` (default an hour).
 *   Needs `LIBRARY_STORE`.
 * - `LIBRARY_TTL_SECONDS`: how long an app keeps a published manifest before asking again (default
 *   an hour; it revalidates by ETag).
 * - `LIBRARY_EDIT_POLL_SECONDS`: how often edits to the library's designs in UI Builder are checked
 *   for and published to `main` ([LibraryEditPublisher]); 0 turns that off. Default 60.
 * - `PORT`: default 8080. `HOST`: default `0.0.0.0`.
 * - UI Builder, optional: `UI_BUILDER_BINARY` runs the bundled one, `UI_BUILDER_URL` +
 *   `UI_BUILDER_TOKEN` use another (see [UiBuilderConfig.fromEnvironment]). With it, a browser can
 *   seed UI Builder designs from card schemas.
 * - Who may: with `HOST_SIGN_IN_URL` (the sign-in of the preview server sharing this host) or
 *   `GITHUB_CLIENT_ID` (+ `GITHUB_CLIENT_SECRET`, `PUBLIC_URL`, `SESSION_SECRET`), plus optional
 *   `GITHUB_USERS`, anyone who signs in with GitHub, each design shared with its designer
 *   ([GithubAuth.fromEnvironment]); without either, a browser that opened the edit link the server
 *   prints.
 * - The front page's links: `DESIGNER_URL`, `CATALOG_URL`, `STORE_URL`
 *   ([SiteConfig.fromEnvironment]).
 * - `USER_DESIGNS_DIR`: where designs people publish are kept ([UserDesignStore]); defaults to
 *   `user-designs` under `UI_BUILDER_STATE_DIR` when that is set. Publishing needs GitHub sign-in.
 * - `USAGE_DIR`: where anonymous template usage counts are kept by day ([UsageStats]); defaults to
 *   `usage` under `UI_BUILDER_STATE_DIR`. Without either, counts last until a restart.
 * - `LIBRARY_EDITORS`: comma-separated GitHub logins that may edit the library's card designs in UI
 *   Builder (`/library/<cardType>/edit`); with GitHub sign-in and none named, nobody may.
 * - `STATS_USERS`: comma-separated GitHub logins that may read `/stats/templates`; empty (the
 *   default) shows it to nobody.
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull() ?: System.getenv("DESIGN_LIBRARY") ?: "card-designs")
  val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
  val host = System.getenv("HOST") ?: "0.0.0.0"
  val store = System.getenv("LIBRARY_STORE")?.takeIf { it.isNotBlank() }?.let(::File)
  val channels =
    LibraryChannels(
      store,
      dir,
      ttlSeconds =
        System.getenv("LIBRARY_TTL_SECONDS")?.toLongOrNull() ?: LibraryChannels.DEFAULT_TTL_SECONDS,
    )
  val holder = channels.main
  val library = holder.current()
  val publishToken = System.getenv("LIBRARY_PUBLISH_TOKEN")?.takeIf { it.isNotBlank() }
  println(
    if (store == null) "library store: none, serving ${dir.absolutePath} read-only"
    else
      "library store: ${store.absolutePath}, channels ${channels.list().map { it.channel }}" +
        if (publishToken == null) " (publishing off: no LIBRARY_PUBLISH_TOKEN)" else ""
  )
  println(
    "card design server: ${library.designs.size} designs (serial ${library.manifest.serial}) " +
      "from ${holder.dir.absolutePath} on http://$host:$port - app URL: <host>$API_BASE"
  )
  // Redirects are answers here (UI Builder's share route, GitHub's), never something to follow.
  val http = HttpClient(io.ktor.client.engine.cio.CIO) { followRedirects = false }
  val userDesignsDir =
    System.getenv("USER_DESIGNS_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
      ?: System.getenv("UI_BUILDER_STATE_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it, "user-designs") }
  val userDesigns = userDesignsDir?.let(::UserDesignStore)
  val github = GithubAuth.fromEnvironment(System.getenv(), http, keyDir = userDesignsDir)
  val uiBuilder =
    UiBuilderConfig.fromEnvironment()?.let { (config, process) ->
      process?.start()
      println(
        "UI Builder: ${config.browserUrl}" +
          (if (process != null) " (bundled)" else "") +
          if (github != null) " - editing: ${github.signInDescription}"
          else
            " - edit link (keep it private, it unlocks editing): " +
              "http://localhost:$port/edit?token=${config.token.encodeURLParameter()}"
      )
      UiBuilderIntegration(config, UiBuilderClient(config, http))
    }
  if (userDesigns != null) println("user designs: revision ${userDesigns.revision}")
  val usage =
    UsageStats(
      System.getenv("USAGE_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: System.getenv("UI_BUILDER_STATE_DIR")
          ?.takeIf { it.isNotBlank() }
          ?.let { File(it, "usage") }
    )
  val statsUsers =
    System.getenv("STATS_USERS")
      .orEmpty()
      .split(',')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .toSet()
  val libraryEditors =
    System.getenv("LIBRARY_EDITORS")
      .orEmpty()
      .split(',')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .toSet()
  val mirror =
    System.getenv("LIBRARY_UPSTREAM")
      ?.takeIf { it.isNotBlank() }
      ?.let { upstream ->
        require(channels.publishable) { "LIBRARY_UPSTREAM is set, so LIBRARY_STORE is needed too" }
        println("library mirror: main follows $upstream")
        LibraryMirror(channels, upstream, http)
      }
  embeddedServer(CIO, port = port, host = host) {
      install(CallLogging)
      designServer(
        holder,
        uiBuilder,
        github,
        SiteConfig.fromEnvironment(),
        userDesigns,
        usage,
        statsUsers,
        libraryEditors,
        channels,
        publishToken,
        editPollMs = (System.getenv("LIBRARY_EDIT_POLL_SECONDS")?.toLongOrNull() ?: 60L) * 1_000,
        mirror = mirror,
        mirrorPollMs =
          (System.getenv("LIBRARY_UPSTREAM_POLL_SECONDS")?.toLongOrNull() ?: 3_600L) * 1_000,
      )
    }
    .start(wait = true)
}
