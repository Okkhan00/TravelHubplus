package com.tabeer.travelhub.data

/** Search / filter / sort logic for the dashboard (pure, unit-tested). */
object PortalFilter {
    /** Suggestions only — the user may type any category. Nothing is created automatically. */
    val SUGGESTED_CATEGORIES = listOf("Flights", "Hotels", "Visa", "Umrah", "Tours", "Finance", "Other")

    fun categories(all: List<Portal>): List<String> =
        all.map { it.category.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    fun apply(
        all: List<Portal>,
        query: String,
        category: String?,
        favoritesOnly: Boolean,
        showDisabled: Boolean,
        sort: SortMode,
    ): List<Portal> {
        val q = query.trim().lowercase()
        val filtered = all.filter { p ->
            (showDisabled || p.enabled) &&
                (!favoritesOnly || p.favorite) &&
                (category == null || p.category.equals(category, ignoreCase = true)) &&
                (q.isEmpty() || p.name.lowercase().contains(q) || p.host.contains(q) || p.category.lowercase().contains(q))
        }
        val base = when (sort) {
            SortMode.MANUAL -> filtered.sortedBy { it.sortIndex }
            SortMode.ALPHABETICAL -> filtered.sortedBy { it.name.lowercase() }
            SortMode.RECENT -> filtered.sortedWith(compareByDescending<Portal> { it.lastOpened }.thenBy { it.name.lowercase() })
        }
        return base.sortedByDescending { it.pinned } // stable: pinned first, order otherwise preserved
    }
}
