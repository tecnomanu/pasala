package com.tecnomanu.pasala.net

import java.net.Inet4Address
import java.net.NetworkInterface

object Addresses {
    data class Local(val lan: List<String>, val tailscale: List<String>) {
        /** Protocol order: LAN first, then Tailscale. Never loopback. */
        val all: List<String> get() = lan + tailscale
    }

    private val VIRTUAL = listOf("docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "lxc", "lxd", "podman", "cni", "flannel")

    fun isTailscale(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        return p.size == 4 && p[0] == 100 && p[1] in 64..127
    }

    fun isPrivateLan(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    fun local(): Local {
        val lan = mutableListOf<Pair<Int, String>>()
        val ts = mutableListOf<String>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in ifaces) {
                if (!runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)) continue
                val name = ni.name.orEmpty()
                // Container and VM bridges (Linux desktops) are private but never reach another device.
                if (VIRTUAL.any { name.startsWith(it) }) continue
                for (addr in ni.inetAddresses.toList()) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    when {
                        isTailscale(ip) -> ts += ip
                        isPrivateLan(ip) -> {
                            // Wi-Fi / Ethernet first, then anything else private (hotspot, USB tether).
                            val rank = when {
                                name.startsWith("wl") -> 0 // wlan0, wlp2s0
                                name.startsWith("eth") || name.startsWith("en") -> 1 // eth0, enp3s0
                                else -> 2
                            }
                            lan += rank to ip
                        }
                    }
                }
            }
        }
        return Local(lan.sortedBy { it.first }.map { it.second }.distinct(), ts.distinct())
    }
}
