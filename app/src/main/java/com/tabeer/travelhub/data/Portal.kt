package com.tabeer.travelhub.data

import org.json.JSONArray
import org.json.JSONObject

enum class OpenMode { IN_APP, EXTERNAL }

/**
 * A user-defined portal. Contains NO secrets: usernames and passwords live only in the encrypted
 * [com.tabeer.travelhub.security.CredentialStore], keyed by [id].
 */
data class Portal(
    val id: String = "",
    val name: String,
    val url: String,
    val category: String = "",
    val notes: String = "",
    val iconText: String = "",
    val colorHex: String = "",
    val favorite: Boolean = false,
    val pinned: Boolean = false,
    val enabled: Boolean = true,
    val openMode: OpenMode = OpenMode.IN_APP,
    val allowHttp: Boolean = false,
    val autoFill: Boolean = false,
    val autoSubmit: Boolean = false,
    val rememberSession: Boolean = true,
    val extraTrustedHosts: List<String> = emptyList(),
    val logoutUrl: String = "",
    val sortIndex: Int = 0,
    val lastOpened: Long = 0L,
    val createdAt: Long = 0L,
) {
    val host: String get() = UrlValidator.hostOf(url).orEmpty()

    /** Hosts to which saved credentials may ever be sent: the portal's own host plus user-added hosts. */
    val trustedHosts: List<String> get() = (listOf(host) + extraTrustedHosts).filter { it.isNotBlank() }.distinct()

    companion object {
        const val MAX_NAME = 80
        const val MAX_NOTES = 2000
        private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        fun validColor(c: String): String = if (COLOR.matches(c)) c.uppercase() else ""
    }
}

/** JSON mapping used for the app's own private storage file. */
object PortalJson {
    fun toJson(p: Portal): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("url", p.url)
        put("category", p.category)
        put("notes", p.notes)
        put("iconText", p.iconText)
        put("colorHex", p.colorHex)
        put("favorite", p.favorite)
        put("pinned", p.pinned)
        put("enabled", p.enabled)
        put("openMode", p.openMode.name)
        put("allowHttp", p.allowHttp)
        put("autoFill", p.autoFill)
        put("autoSubmit", p.autoSubmit)
        put("rememberSession", p.rememberSession)
        put("extraTrustedHosts", JSONArray(p.extraTrustedHosts))
        put("logoutUrl", p.logoutUrl)
        put("sortIndex", p.sortIndex)
        put("lastOpened", p.lastOpened)
        put("createdAt", p.createdAt)
    }

    /** Returns null for records that fail validation so one bad record can't break the whole list. */
    fun fromJson(o: JSONObject): Portal? {
        val id = o.optString("id")
        val name = o.optString("name").trim()
        if (id.isEmpty() || name.isEmpty()) return null
        val url = (UrlValidator.normalize(o.optString("url")) as? UrlValidator.Result.Ok)?.url ?: return null
        val hosts = o.optJSONArray("extraTrustedHosts")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { UrlValidator.isValidHost(it) }
        }.orEmpty()
        return Portal(
            id = id,
            name = name.take(Portal.MAX_NAME),
            url = url,
            category = o.optString("category").take(40),
            notes = o.optString("notes").take(Portal.MAX_NOTES),
            iconText = o.optString("iconText").take(2),
            colorHex = Portal.validColor(o.optString("colorHex")),
            favorite = o.optBoolean("favorite"),
            pinned = o.optBoolean("pinned"),
            enabled = o.optBoolean("enabled", true),
            openMode = runCatching { OpenMode.valueOf(o.optString("openMode")) }.getOrDefault(OpenMode.IN_APP),
            allowHttp = o.optBoolean("allowHttp") && url.startsWith("http://"),
            autoFill = o.optBoolean("autoFill"),
            autoSubmit = o.optBoolean("autoSubmit") && o.optBoolean("autoFill"),
            rememberSession = o.optBoolean("rememberSession", true),
            extraTrustedHosts = hosts,
            logoutUrl = o.optString("logoutUrl"),
            sortIndex = o.optInt("sortIndex"),
            lastOpened = o.optLong("lastOpened"),
            createdAt = o.optLong("createdAt"),
        )
    }
}
