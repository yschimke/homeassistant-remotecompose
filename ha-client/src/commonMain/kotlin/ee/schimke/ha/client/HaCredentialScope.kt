package ee.schimke.ha.client

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.takeFrom

/**
 * Whether a request to [requestUrl] may carry the Home Assistant bearer token of the instance at
 * [instanceUrl].
 *
 * Only the instance's own origin qualifies: the host matches (case-insensitively), the effective
 * port matches (an omitted port is the scheme's default: 80 for http, 443 for https), and the
 * scheme is http or https and never a downgrade. An http instance's token may go to https on the
 * same host and effective port, which only happens with an explicit port; an https instance's token
 * never goes over plain http. A different port on the same host is a different service and gets
 * nothing.
 *
 * [requestUrl] may be relative (`/api/image_proxy/...`, as HA returns for `entity_picture`): it is
 * resolved against [instanceUrl] first, so such paths always qualify. Anything that fails to parse
 * is refused.
 */
fun haBearerAllowed(instanceUrl: String, requestUrl: String): Boolean {
  val instance = runCatching { Url(instanceUrl) }.getOrNull() ?: return false
  if (instance.protocol !in WEB || instance.host.isEmpty()) return false
  val request =
    runCatching { URLBuilder(instance).takeFrom(requestUrl).build() }.getOrNull() ?: return false
  if (request.protocol !in WEB) return false
  if (instance.protocol == URLProtocol.HTTPS && request.protocol == URLProtocol.HTTP) return false
  return request.host.equals(instance.host, ignoreCase = true) && request.port == instance.port
}

private val WEB = setOf(URLProtocol.HTTP, URLProtocol.HTTPS)
