package com.tabeer.travelhub.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PortalRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file() = File(tmp.root, "portals.json")
    private fun repo() = PortalRepository(file())
    private fun draft(name: String = "Test Portal", url: String = "https://example.com") = Portal(name = name, url = url)

    @Test fun startsEmpty() {
        assertTrue(repo().all().isEmpty())
    }

    @Test fun addAcceptsUserEnteredNameAndUrl() {
        val r = repo()
        val res = r.add(draft("My Portal", "portal.example.org")) as PortalRepository.SaveResult.Saved
        assertEquals("My Portal", res.portal.name)
        assertEquals("https://portal.example.org/", res.portal.url)
        assertTrue(res.portal.id.isNotEmpty())
        assertEquals(1, r.all().size)
    }

    @Test fun rejectsBlankNameAndBadUrl() {
        val r = repo()
        assertTrue(r.add(draft(name = "  ")) is PortalRepository.SaveResult.Invalid)
        assertTrue(r.add(draft(url = "javascript:alert(1)")) is PortalRepository.SaveResult.Invalid)
        assertTrue(r.all().isEmpty())
    }

    @Test fun httpRequiresExplicitOptIn() {
        val r = repo()
        assertTrue(r.add(draft(url = "http://example.com")) is PortalRepository.SaveResult.Invalid)
        val ok = r.add(draft(url = "http://example.com").copy(allowHttp = true)) as PortalRepository.SaveResult.Saved
        assertTrue(ok.portal.allowHttp)
    }

    @Test fun duplicateUrlDetected() {
        val r = repo()
        r.add(draft("A", "https://example.com/x"))
        val dup = r.add(draft("B", "http://EXAMPLE.com/x/").copy(allowHttp = true))
        assertTrue(dup is PortalRepository.SaveResult.Duplicate)
        assertEquals(1, r.all().size)
    }

    @Test fun editKeepsIdAndOrderAndDetectsDuplicates() {
        val r = repo()
        val a = (r.add(draft("A", "https://a.example.com")) as PortalRepository.SaveResult.Saved).portal
        val b = (r.add(draft("B", "https://b.example.com")) as PortalRepository.SaveResult.Saved).portal
        val edited = (r.update(a.copy(name = "A2", category = "Hotels")) as PortalRepository.SaveResult.Saved).portal
        assertEquals(a.id, edited.id)
        assertEquals(a.sortIndex, edited.sortIndex)
        assertEquals("A2", r.get(a.id)!!.name)
        assertTrue(r.update(b.copy(url = "https://a.example.com")) is PortalRepository.SaveResult.Duplicate)
        // editing a portal to its own URL is not a duplicate
        assertTrue(r.update(a.copy(name = "A3")) is PortalRepository.SaveResult.Saved)
    }

    @Test fun deleteRemoves() {
        val r = repo()
        val a = (r.add(draft()) as PortalRepository.SaveResult.Saved).portal
        assertTrue(r.delete(a.id))
        assertFalse(r.delete(a.id))
        assertTrue(r.all().isEmpty())
    }

    @Test fun dataPersistsAcrossInstances() {
        val a = (repo().add(draft("Persist", "https://persist.example.com").copy(category = "Visa", favorite = true)) as PortalRepository.SaveResult.Saved).portal
        val loaded = repo().all().single()
        assertEquals(a, loaded)
        assertEquals("Visa", loaded.category)
        assertTrue(loaded.favorite)
    }

    @Test fun corruptedFileIsQuarantinedAndAppStartsEmpty() {
        file().writeText("{ this is not json")
        val r = repo()
        assertTrue(r.all().isEmpty())
        assertTrue(File(file().path + ".corrupt").exists())
        // still usable afterwards
        assertTrue(r.add(draft()) is PortalRepository.SaveResult.Saved)
    }

    @Test fun moveReorders() {
        val r = repo()
        val a = (r.add(draft("A", "https://a.example.com")) as PortalRepository.SaveResult.Saved).portal
        val b = (r.add(draft("B", "https://b.example.com")) as PortalRepository.SaveResult.Saved).portal
        r.move(b.id, -1)
        assertEquals(listOf("B", "A"), r.all().map { it.name })
        r.move(b.id, -1) // already first: no change
        assertEquals(listOf("B", "A"), r.all().map { it.name })
        assertNotNull(a)
    }

    @Test fun autoSubmitRequiresAutoFill() {
        val p = (repo().add(draft().copy(autoFill = false, autoSubmit = true)) as PortalRepository.SaveResult.Saved).portal
        assertFalse(p.autoSubmit)
    }

    @Test fun portalJsonFileContainsNoCredentialFields() {
        repo().add(draft())
        val text = file().readText().lowercase()
        assertFalse(text.contains("password"))
        assertFalse(text.contains("username"))
    }
}
