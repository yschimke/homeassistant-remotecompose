package ee.schimke.terrazzo.image

import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import ee.schimke.terrazzo.core.session.DemoData

/**
 * Serves demo-mode pictures from [DemoImages] instead of the network.
 *
 * The demo session's base URL is [DemoData.BASE_URL] (`demo://terrazzo`), so every picture the demo
 * dashboards reference — camera frames, image entities, entity-picture icons — resolves to a URL on
 * that host, which nothing serves. [Factory] answers only for those URLs; a real session's URLs are
 * `http(s)` on its own host and never reach this fetcher, so it changes nothing outside demo mode.
 */
class DemoImageFetcher(private val url: String) : Fetcher {

  override suspend fun fetch(): FetchResult? {
    val bitmap = DemoImages.render(url) ?: return null
    return ImageFetchResult(
      image = bitmap.asImage(),
      isSampled = false,
      dataSource = DataSource.MEMORY,
    )
  }

  class Factory : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
      val url = data.toString()
      return if (DemoImages.isDemoUrl(url)) DemoImageFetcher(url) else null
    }
  }
}
