package com.tabeer.travelhub.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Export/import of NON-SECRET portal configuration.
 * Usernames, passwords, cookies and sessions are never part of a backup.
 * For safety, security-relevant login settings (auto-fill, auto-submit, extra trusted domains,
 * http opt-in) are deliberately not exported or restored: they must be re-confirmed by the user.
 */
object BackupCodec {
    const val FORMAT = "travelhub-portals"
    const val VERSION = 1
    const val MAX_BYTES = 1_000_000
    const val MAX_PORTALS = 500

    data class Parsed(val portals: List<Portal>, val rejected: Int)

    sealed class ImportResult {
        data class Ok(val parsed: Parsed) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    fun export(portals: List<Portal>, exportedAt: Long): String {
        val arr = JSONArray()
        portals.sortedBy { it.sortIndex }.forEach { p ->
            arr.put(JSONObject().apply {
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
                put("rememberSession", p.rememberSession)
                put("logoutUrl", p.logoutUrl)
            })
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", exportedAt)
            .put("note", "Contains no usernames or passwords. Re-enter credentials after restoring.")
            .put("portals", arr)
            .toString(2)
    }

    fun parse(text: String): ImportResult {
        if (text.length > MAX_BYTES) return ImportResult.Error("The file is too large to be a TravelHub backup")
        val root = try { JSONObject(text) } catch (e: Exception) { return ImportResult.Error("This is not a valid TravelHub backup file") }
        if (root.optString("format") != FORMAT) return ImportResult.Error("This is not a TravelHub portal backup")
        if (root.optInt("version", 0) !in 1..VERSION) return ImportResult.Error("Unsupported backup version")
        val arr = root.optJSONArray("portals") ?: return ImportResult.Error("The backup contains no portal list")
        if (arr.length() > MAX_PORTALS) return ImportResult.Error("The backup contains too many portals")
        val out = ArrayList<Portal>()
        var rejected = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            val name = o?.optString("name")?.trim().orEmpty()
            val url = (o?.let { UrlValidator.normalize(it.optString("url")) } as? UrlValidator.Result.Ok)
            // http:// portals are not restored: that choice needs an explicit warning/confirmation.
            if (o == null || name.isEmpty() || url == null || url.isHttp) { rejected++; continue }
            val logout = (UrlValidator.normalizeLogoutUrl(o.optString("logoutUrl")) as? UrlValidator.Result.Ok)?.url.orEmpty()
            out.add(
                Portal(
                    name = name.take(Portal.MAX_NAME),
                    url = url.url,
                    category = o.optString("category").trim().take(40),
                    notes = o.optString("notes").take(Portal.MAX_NOTES),
                    iconText = o.optString("iconText").trim().take(2),
                    colorHex = Portal.validColor(o.optString("colorHex")),
                    favorite = o.optBoolean("favorite"),
                    pinned = o.optBoolean("pinned"),
                    enabled = o.optBoolean("enabled", true),
                    openMode = runCatching { OpenMode.valueOf(o.optString("openMode")) }.getOrDefault(OpenMode.IN_APP),
                    rememberSession = o.optBoolean("rememberSession", true),
                    logoutUrl = logout,
                )
            )
        }
        return ImportResult.Ok(Parsed(out, rejected))
    }
}
