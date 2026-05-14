package ee.schimke.terrazzo.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides whether a base URL should be attempted from the device's current network.
 *
 * Used in two places:
 *  * Connect-time: before launching AppAuth so a bad URL surfaces immediately.
 *  * Per-request: via [LanConnectionPolicyInterceptor] on the OkHttp engine, so any code path that
 *    later builds a request can't slip past the gate.
 *
 * Rules:
 *  * `https://…` is always allowed (TLS doesn't care about transport).
 *  * `http://…` to a LAN destination (loopback, RFC1918, link-local, `*.local`, `*.home.arpa`,
 *    `*.lan`, `*.home`, `*.internal`, the emulator host `10.0.2.2`, or a single-label hostname
 *    like `homeassistant`) is allowed only over Wi-Fi or Ethernet — never cellular.
 *  * `http://…` to a public destination is rejected.
 *
 * Caching: the active-network transport is read once via [ConnectivityManager.registerDefaultNetworkCallback]
 * and refreshed when Android announces a change (network gained/lost, capabilities changed). Per-request
 * lookups never hit ConnectivityManager themselves — they read the cached [AtomicReference]. The host
 * classification is pure and stateless.
 */
@SingleIn(AppScope::class)
@Inject
class LanConnectionPolicy(context: Context) {

  private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val transport = AtomicReference(Transport.None)

  init {
    // Seed before the first callback fires, so requests issued in the
    // first few milliseconds after construction see a real value rather
    // than `None`.
    transport.set(readTransport(connectivity.activeNetwork))
    connectivity.registerDefaultNetworkCallback(
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          transport.set(readTransport(network))
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
          transport.set(transportFromCaps(caps))
        }

        override fun onLost(network: Network) {
          transport.set(Transport.None)
        }
      }
    )
  }

  sealed class Verdict {
    object Allow : Verdict()

    data class Deny(val reason: String) : Verdict()
  }

  fun check(baseUrl: String): Verdict {
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return Verdict.Deny("Invalid URL")
    val scheme = uri.scheme?.lowercase()
    val host = uri.host ?: return Verdict.Deny("URL is missing a host")

    if (scheme == "https") return Verdict.Allow
    if (scheme != "http") return Verdict.Deny("Only http and https are supported")

    if (!isLanHost(host)) {
      return Verdict.Deny(
        "Plaintext HTTP is only permitted to a local-network address. " +
          "Use HTTPS, or enter a LAN hostname / private IP."
      )
    }

    return when (transport.get()) {
      Transport.WifiOrEthernet -> Verdict.Allow
      Transport.Cellular ->
        Verdict.Deny(
          "Refusing plaintext HTTP to a local-network address over cellular. " +
            "Connect to the home Wi-Fi network and try again."
        )
      Transport.None -> Verdict.Deny("No network available")
    }
  }

  private fun readTransport(network: Network?): Transport {
    val n = network ?: return Transport.None
    val caps = connectivity.getNetworkCapabilities(n) ?: return Transport.None
    return transportFromCaps(caps)
  }

  private enum class Transport {
    WifiOrEthernet,
    Cellular,
    None,
  }

  companion object {
    // Suffixes commonly used for LAN-scoped names. `.local` is mDNS
    // (RFC6762); `.home.arpa` is RFC8375; the others are de-facto
    // defaults shipped by consumer routers (Fritz!Box, UniFi, OpenWrt,
    // pfSense).
    private val LAN_SUFFIXES = listOf(".local", ".home.arpa", ".lan", ".home", ".internal")

    fun isLanHost(host: String): Boolean {
      val h = host.trim().trimEnd('.').lowercase()
      if (h.isEmpty()) return false
      if (h == "localhost") return true
      // Single-label hostnames (no dot) are LAN-only — DNS won't
      // resolve them on the public internet.
      if ('.' !in h && '[' !in h) return true
      if (LAN_SUFFIXES.any { h.endsWith(it) }) return true
      return parseLiteral(h)?.let(::isPrivateAddress) ?: false
    }

    private fun transportFromCaps(caps: NetworkCapabilities): Transport =
      when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WifiOrEthernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.WifiOrEthernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.Cellular
        else -> Transport.None
      }

    private fun parseLiteral(host: String): InetAddress? {
      val stripped =
        if (host.startsWith("[") && host.endsWith("]")) host.substring(1, host.length - 1) else host
      if (
        !stripped.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
      ) {
        return null
      }
      return runCatching { InetAddress.getByName(stripped) }.getOrNull()
    }

    private fun isPrivateAddress(addr: InetAddress): Boolean =
      when (addr) {
        is Inet4Address ->
          addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress // RFC1918: 10/8, 172.16/12, 192.168/16
        is Inet6Address ->
          addr.isLoopbackAddress ||
            addr.isLinkLocalAddress || // fe80::/10
            addr.isSiteLocalAddress ||
            isUniqueLocal(addr) // fc00::/7
        else -> false
      }

    private fun isUniqueLocal(addr: Inet6Address): Boolean {
      val first = addr.address[0].toInt() and 0xFE
      return first == 0xFC
    }
  }
}
