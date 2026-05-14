package ee.schimke.terrazzo.discovery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanConnectionPolicyTest {

    @Test
    fun loopback_and_emulator_are_lan() {
        assertTrue(LanConnectionPolicy.isLanHost("127.0.0.1"))
        assertTrue(LanConnectionPolicy.isLanHost("localhost"))
        assertTrue(LanConnectionPolicy.isLanHost("10.0.2.2"))
        assertTrue(LanConnectionPolicy.isLanHost("::1"))
    }

    @Test
    fun rfc1918_addresses_are_lan() {
        assertTrue(LanConnectionPolicy.isLanHost("192.168.1.50"))
        assertTrue(LanConnectionPolicy.isLanHost("10.0.0.1"))
        assertTrue(LanConnectionPolicy.isLanHost("172.16.0.1"))
        assertTrue(LanConnectionPolicy.isLanHost("172.31.255.254"))
    }

    @Test
    fun public_ipv4_addresses_are_not_lan() {
        assertFalse(LanConnectionPolicy.isLanHost("8.8.8.8"))
        assertFalse(LanConnectionPolicy.isLanHost("172.32.0.1")) // just outside RFC1918
        assertFalse(LanConnectionPolicy.isLanHost("11.0.0.1"))
    }

    @Test
    fun mdns_and_router_suffixes_are_lan() {
        assertTrue(LanConnectionPolicy.isLanHost("homeassistant.local"))
        assertTrue(LanConnectionPolicy.isLanHost("hassio.home.arpa"))
        assertTrue(LanConnectionPolicy.isLanHost("ha.lan"))
        assertTrue(LanConnectionPolicy.isLanHost("ha.home"))
        assertTrue(LanConnectionPolicy.isLanHost("ha.internal"))
        // Trailing dot (fully-qualified mDNS form) should still match.
        assertTrue(LanConnectionPolicy.isLanHost("homeassistant.local."))
    }

    @Test
    fun single_label_hostname_is_lan() {
        // "homeassistant" / "hassio" — won't resolve on public DNS.
        assertTrue(LanConnectionPolicy.isLanHost("homeassistant"))
        assertTrue(LanConnectionPolicy.isLanHost("hassio"))
    }

    @Test
    fun public_hostnames_are_not_lan() {
        assertFalse(LanConnectionPolicy.isLanHost("example.com"))
        assertFalse(LanConnectionPolicy.isLanHost("home-assistant.io"))
        assertFalse(LanConnectionPolicy.isLanHost("nabu.casa"))
    }

    @Test
    fun ipv6_link_local_and_ula_are_lan() {
        assertTrue(LanConnectionPolicy.isLanHost("fe80::1"))
        assertTrue(LanConnectionPolicy.isLanHost("fc00::1"))
        assertTrue(LanConnectionPolicy.isLanHost("fd12:3456:789a::1"))
    }
}
