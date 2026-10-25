package com.tabeer.travelhub.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists the portal list as JSON (no secrets) in app-private storage.
 * The list starts EMPTY: nothing is ever pre-populated.
 */
class PortalRepository(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    sealed class SaveResult {
        data class Saved(val portal: Portal) : SaveResult()
        data class Duplicate(val existing: Portal) : SaveResult()
        data class Invalid(val message: String) : SaveResult()
    }

    data class ImportSummary(val added: Int, val duplicates: Int, val invalid: Int)

    private var items: MutableList<Portal> = load()

    @Synchronized fun all(): List<Portal> = items.sortedBy { it.sortIndex }

    @Synchronized fun get(id: String): Portal? = items.firstOrNull { it.id == id }

    @Synchronized fun add(draft: Portal): SaveResult {
        val clean = validate(draft)
        if (clean is Checked.Bad) return SaveResult.Invalid(clean.message)
        clean as Checked.Good
        findDuplicate(clean.portal, null)?.let { return SaveResult.Duplicate(it) }
        val saved = clean.portal.copy(
            id = UUID.randomUUID().toString(),
            sortIndex = (items.maxOfOrNull { it.sortIndex } ?: -1) + 1,
            createdAt = clock(),
        )
        items.add(saved)
        persist()
        return SaveResult.Saved(saved)
    }

    @Synchronized fun update(edited: Portal): SaveResult {
        val old = items.firstOrNull { it.id == edited.id } ?: return SaveResult.Invalid("This portal no longer exists")
        val clean = validate(edited)
        if (clean is Checked.Bad) return SaveResult.Invalid(clean.message)
        clean as Checked.Good
        findDuplicate(clean.portal, old.id)?.let { return SaveResult.Duplicate(it) }
        val saved = clean.portal.copy(sortIndex = old.sortIndex, createdAt = old.createdAt, lastOpened = old.lastOpened)
        items[items.indexOfFirst { it.id == old.id }] = saved
        persist()
        return SaveResult.Saved(saved)
    }

    /** Applies a flag-style change (favorite, pin, enable…) without re-validation. */
    @Synchronized fun modify(id: String, change: (Portal) -> Portal): Portal? {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return null
        items[i] = change(items[i]).copy(id = id)
        persist()
        return items[i]
    }

    @Synchronized fun delete(id: String): Boolean {
        val removed = items.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    @Synchronized fun markOpened(id: String) {
        modify(id) { it.copy(lastOpened = clock()) }
    }

    /** Moves a portal up (-1) or down (+1) in the manual order. */
    @Synchronized fun move(id: String, delta: Int) {
        val sorted = items.sortedBy { it.sortIndex }.toMutableList()
        val i = sorted.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in sorted.indices) return
        val tmp = sorted[i]; sorted[i] = sorted[j]; sorted[j] = tmp
        items = sorted.mapIndexed { idx, p -> p.copy(sortIndex = idx) }.toMutableList()
        persist()
    }

    @Synchronized fun importAll(incoming: List<Portal>): ImportSummary {
        var added = 0; var dup = 0; var bad = 0
        for (p in incoming) when (add(p.copy(id = ""))) {
            is SaveResult.Saved -> added++
            is SaveResult.Duplicate -> dup++
            is SaveResult.Invalid -> bad++
        }
        return ImportSummary(added, dup, bad)
    }

    @Synchronized fun clear() {
        items.clear()
        persist()
    }

    // ---- internals ----

    private sealed class Checked {
        data class Good(val portal: Portal) : Checked()
        data class Bad(val message: String) : Checked()
    }

    private fun validate(p: Portal): Checked {
        val name = p.name.trim()
        if (name.isEmpty()) return Checked.Bad("Enter a portal name")
        if (name.length > Portal.MAX_NAME) return Checked.Bad("The name is too long")
        val n = UrlValidator.normalize(p.url)
        if (n is UrlValidator.Result.Error) return Checked.Bad(n.message)
        n as UrlValidator.Result.Ok
        if (n.isHttp && !p.allowHttp) return Checked.Bad("Plain http:// was not confirmed for this portal")
        val hosts = p.extraTrustedHosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (hosts.any { !UrlValidator.isValidHost(it) }) return Checked.Bad("An extra trusted domain is invalid")
        val logout = UrlValidator.normalizeLogoutUrl(p.logoutUrl)
        if (logout is UrlValidator.Result.Error) return Checked.Bad(logout.message)
        logout as UrlValidator.Result.Ok
        return Checked.Good(
            p.copy(
                name = name,
                url = n.url,
                allowHttp = n.isHttp && p.allowHttp,
                category = p.category.trim().take(40),
                notes = p.notes.take(Portal.MAX_NOTES),
                iconText = p.iconText.trim().take(2),
                colorHex = Portal.validColor(p.colorHex),
                extraTrustedHosts = hosts.distinct(),
                logoutUrl = logout.url,
                autoSubmit = p.autoSubmit && p.autoFill,
            )
        )
    }

    private fun findDuplicate(p: Portal, ignoreId: String?): Portal? {
        val key = UrlValidator.duplicateKey(p.url)
        return items.firstOrNull { it.id != ignoreId && UrlValidator.duplicateKey(it.url) == key }
    }

    private fun persist() {
        val root = JSONObject().put("version", 1).put("portals", JSONArray(items.map { PortalJson.toJson(it) }))
        FileIo.writeAtomic(file, root.toString())
    }

    private fun load(): MutableList<Portal> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("portals")
            (0 until arr.length()).mapNotNull { PortalJson.fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) {
            FileIo.quarantine(file)
            mutableListOf()
        }
    }
}
