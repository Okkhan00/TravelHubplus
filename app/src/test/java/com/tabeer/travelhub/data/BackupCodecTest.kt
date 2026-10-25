package com.tabeer.travelhub.data

import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val p = Portal(
        id = "x", name = "My Portal", url = "https://example.com/", category = "Visa", notes = "n",
        favorite = true, autoFill = true, autoSubmit = true, allowHttp = false,
        extraTrustedHosts = listOf("sso.example.com"), logoutUrl = "https://example.com/logout",
    )

    @Test fun exportHasNoSecretsOrSecurityRelevantLoginSettings() {
        val json = BackupCodec.export(listOf(p), 1L).lowercase()
        assertFalse(json.contains("password"))
        assertFalse(json.contains("username"))
        assertFalse(json.contains("autofill"))
        assertFalse(json.contains("trustedhosts"))
        assertTrue(json.contains("my portal"))
    }

    @Test fun roundTripRestoresConfigurationButResetsLoginAutomation() {
        val parsed = (BackupCodec.parse(BackupCodec.export(listOf(p), 1L)) as BackupCodec.ImportResult.Ok).parsed
        val r = parsed.portals.single()
        assertEquals("My Portal", r.name)
        assertEquals("Visa", r.category)
        assertTrue(r.favorite)
        assertFalse(r.autoFill)
        assertFalse(r.autoSubmit)
        assertTrue(r.extraTrustedHosts.isEmpty())
        assertEquals(0, parsed.rejected)
    }

    @Test fun rejectsGarbageAndWrongFormat() {
        assertTrue(BackupCodec.parse("nope") is BackupCodec.ImportResult.Error)
        assertTrue(BackupCodec.parse("""{"format":"other","version":1,"portals":[]}""") is BackupCodec.ImportResult.Error)
        assertTrue(BackupCodec.parse("""{"format":"travelhub-portals","version":99,"portals":[]}""") is BackupCodec.ImportResult.Error)
        assertTrue(BackupCodec.parse("""{"format":"travelhub-portals","version":1}""") is BackupCodec.ImportResult.Error)
        assertTrue(BackupCodec.parse("x".repeat(BackupCodec.MAX_BYTES + 1)) is BackupCodec.ImportResult.Error)
    }

    @Test fun skipsInvalidAndHttpEntries() {
        val text = """{"format":"travelhub-portals","version":1,"portals":[
            {"name":"Good","url":"https://good.example.com"},
            {"name":"","url":"https://noname.example.com"},
            {"name":"Evil","url":"javascript:alert(1)"},
            {"name":"Plain","url":"http://plain.example.com"},
            "junk"
        ]}"""
        val parsed = (BackupCodec.parse(text) as BackupCodec.ImportResult.Ok).parsed
        assertEquals(listOf("Good"), parsed.portals.map { it.name })
        assertEquals(4, parsed.rejected)
    }

    @Test fun importIntoRepositorySkipsDuplicates() {
        val dir = java.nio.file.Files.createTempDirectory("th").toFile()
        val repo = PortalRepository(java.io.File(dir, "p.json"))
        repo.add(Portal(name = "Existing", url = "https://example.com/"))
        val parsed = (BackupCodec.parse(BackupCodec.export(listOf(p), 1L)) as BackupCodec.ImportResult.Ok).parsed
        val s = repo.importAll(parsed.portals)
        assertEquals(0, s.added)
        assertEquals(1, s.duplicates)
        dir.deleteRecursively()
    }
}
