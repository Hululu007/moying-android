package com.moying.app

import java.net.URL
import java.security.MessageDigest

internal object MediaRequestHeaders {
    private val allowed = listOf("Accept", "Origin", "Referer", "User-Agent")
    private const val MaxValueLength = 2048

    fun normalize(values: Map<String, String>): Map<String, String> {
        if (values.isEmpty()) return emptyMap()
        val canonical = mutableMapOf<String, String>()
        for ((name, value) in values) {
            val key = allowed.firstOrNull { it.equals(name.trim(), ignoreCase = true) } ?: continue
            if (value.length > MaxValueLength || value.any { it !in ' '..'~' }) continue
            if (value.isNotBlank()) canonical[key] = value.trim()
        }
        return allowed.mapNotNull { key -> canonical[key]?.let { key to it } }.toMap()
    }

    fun sameOrigin(origin: URL, current: URL): Boolean =
        origin.protocol.equals(current.protocol, ignoreCase = true) &&
            origin.host.equals(current.host, ignoreCase = true) &&
            origin.effectivePort() == current.effectivePort()

    fun forRedirect(values: Map<String, String>, origin: URL, current: URL): Map<String, String> {
        val headers = normalize(values)
        return if (sameOrigin(origin, current)) headers else headers.filterKeys { it == "Accept" || it == "User-Agent" }
    }

    fun fingerprint(values: Map<String, String>): String {
        val headers = normalize(values)
        if (headers.isEmpty()) return ""
        val encoded = headers.entries.joinToString("\u0000") { (name, value) -> "$name\u0000$value" }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun URL.effectivePort(): Int = if (port >= 0) port else defaultPort
}
