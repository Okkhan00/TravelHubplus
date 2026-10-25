package com.tabeer.travelhub.data

import java.net.URI
import java.net.URISyntaxException

/** Pure (JVM-testable) URL and host validation for portal addresses. */
object UrlValidator {
    sealed class Result {
        data class Ok(val url: String, val isHttp: Boolean) : Result()
        data class Error(val message: String) : Result()
    }

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val HOST = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    private val IPV4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")

    fun isValidHost(host: String): Boolean = HOST.matches(host) || IPV4.matches(host)

    /**
     * Normalizes user input to an absolute URL. A missing scheme is treated as https:// — the app
     * never silently downgrades to http. http:// input is accepted but flagged via [Result.Ok.isHttp]
     * so the UI can warn before saving.
     */
    fun normalize(input: String): Result {
        val t = input.trim()
        if (t.isEmpty()) return Result.Error("Enter the website address")
        if (t.length > 2048) return Result.Error("The address is too long")
        if (t.any { it.isWhitespace() || it.isISOControl() }) return Result.Error("The address must not contain spaces")
        val candidate = if (SCHEME.containsMatchIn(t)) t else "https://$t"
        val uri = try {
            URI(candidate)
        } catch (e: URISyntaxException) {
            return Result.Error("That doesn't look like a valid address")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return Result.Error("Only https:// (or http://) addresses are supported")
        if (uri.rawUserInfo != null) return Result.Error("Don't put a username or password in the address")
        val host = uri.host?.lowercase() ?: return Result.Error("That doesn't look like a valid address")
        if (!isValidHost(host)) return Result.Error("Enter a full domain name such as example.com")
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return Result.Ok("$scheme://$host$port$path$query$fragment", scheme == "http")
    }

    fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (e: URISyntaxException) {
        null
    }

    /** Key used to detect duplicate portals: scheme-insensitive, ignores a trailing slash. */
    fun duplicateKey(url: String): String = try {
        val u = URI(url)
        val port = if (u.port != -1) ":${u.port}" else ""
        (u.host.orEmpty().lowercase() + port + u.rawPath.orEmpty().trimEnd('/') + u.rawQuery?.let { "?$it" }.orEmpty())
    } catch (e: Exception) {
        url.lowercase()
    }

    /** Parses a comma/space separated list of extra trusted hostnames. Returns null if any entry is invalid. */
    fun parseHosts(text: String): List<String>? {
        val parts = text.split(',', ' ', '\n', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (parts.size > 10) return null
        if (parts.any { !isValidHost(it) }) return null
        return parts.distinct()
    }

    /** Validates an optional sign-out URL: must be a normal https:// address. */
    fun normalizeLogoutUrl(input: String): Result {
        if (input.isBlank()) return Result.Ok("", false)
        val r = normalize(input)
        return if (r is Result.Ok && r.isHttp) Result.Error("The sign-out address must use https://") else r
    }
}

/** Decides whether the page currently shown is on a domain the user trusts for this portal. */
object TrustedDomain {
    /**
     * True only if [url] is https (or http when the portal was explicitly opted in), carries no
     * embedded user info, and its host equals — or is a subdomain of — one of [trustedHosts].
     * Used before any credential is filled, and again immediately before script injection.
     */
    fun isTrusted(url: String?, trustedHosts: List<String>, allowHttp: Boolean): Boolean {
        if (url == null) return false
        val u = try { URI(url) } catch (e: URISyntaxException) { return false }
        val scheme = u.scheme?.lowercase()
        if (scheme != "https" && !(scheme == "http" && allowHttp)) return false
        if (u.rawUserInfo != null) return false
        val host = u.host?.lowercase() ?: return false
        return trustedHosts.any { t ->
            val th = t.trim().lowercase()
            th.isNotEmpty() && (host == th || host.endsWith(".$th"))
        }
    }
}
