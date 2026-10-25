package com.tabeer.travelhub.data

import org.junit.Assert.*
import org.junit.Test

class PortalFilterTest {
    private val a = Portal(id = "1", name = "Alpha Hotels", url = "https://alpha.example.com/", category = "Hotels", sortIndex = 0, lastOpened = 5)
    private val b = Portal(id = "2", name = "Bravo Flights", url = "https://bravo.example.net/", category = "Flights", sortIndex = 1, favorite = true, lastOpened = 9)
    private val c = Portal(id = "3", name = "Charlie", url = "https://charlie.example.org/", category = "hotels", sortIndex = 2, enabled = false, pinned = true)
    private val all = listOf(a, b, c)

    private fun f(q: String = "", cat: String? = null, fav: Boolean = false, disabled: Boolean = false, sort: SortMode = SortMode.MANUAL) =
        PortalFilter.apply(all, q, cat, fav, disabled, sort).map { it.id }

    @Test fun hidesDisabledByDefault() = assertEquals(listOf("1", "2"), f())
    @Test fun showsDisabledAndPinnedFirst() = assertEquals(listOf("3", "1", "2"), f(disabled = true))
    @Test fun searchByNameAndDomain() {
        assertEquals(listOf("2"), f(q = "bravo"))
        assertEquals(listOf("1"), f(q = "alpha.example"))
    }
    @Test fun categoryFilterIsCaseInsensitive() = assertEquals(listOf("3", "1"), f(cat = "Hotels", disabled = true))
    @Test fun favoritesOnly() = assertEquals(listOf("2"), f(fav = true))
    @Test fun sortRecentAndAlpha() {
        assertEquals(listOf("2", "1"), f(sort = SortMode.RECENT))
        assertEquals(listOf("1", "2"), f(sort = SortMode.ALPHABETICAL))
    }
    @Test fun categoriesDeduplicatedCaseInsensitively() = assertEquals(listOf("Flights", "Hotels"), PortalFilter.categories(all))
}
