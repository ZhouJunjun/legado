package io.legado.app.web.mcp

import java.net.InetAddress
import java.security.MessageDigest

object McpAccess {

    const val PATH = "/mcp"
    const val TOKEN_HEADER = "X-Legado-Token"

    /** 常量时间比较, 避免时序侧信道 */
    fun matchesToken(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrBlank() || actual == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )
    }

    fun allowedHosts(addresses: List<InetAddress>): List<String> = buildList {
        add("localhost")
        add("127.0.0.1")
        add("[::1]")
        addresses.mapTo(this) { it.hostAddress }
    }.distinct()

    fun allowedOrigins(hosts: List<String>): List<String> {
        return hosts.map { "http://$it" }
    }

    fun endpointUrls(addresses: List<InetAddress>, port: Int): List<String> {
        val hosts = addresses.map { it.hostAddress }.ifEmpty { listOf("127.0.0.1") }
        return hosts.map { "http://$it:$port$PATH" }
    }
}
