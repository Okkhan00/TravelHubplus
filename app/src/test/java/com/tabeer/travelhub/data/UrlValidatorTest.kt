package com.tabeer.travelhub.data

import org.junit.Assert.*
import org.junit.Test

class UrlValidatorTest {
    private fun ok(s: String) = UrlValidator.normalize(s) as UrlValidator.Result.Ok
    private fun bad(s: String) = assertTrue("expected error for '$s'", UrlValidator.normalize(s) is UrlValidator.Result.Error)

    @Test fun missingSchemeBecomesHttps() {
        val r = ok("Example.com")
        assertEquals("https://example.com/", r.url)
        assertFalse(r.isHttp)
    }

    @Test fun httpIsFlaggedNotDowngradedOrUpgraded() {
        val r = ok("http://example.com/login")
        assertTrue(r.isHttp)
        assertEquals("http://example.com/login", r.url)
    }

    @Test fun keepsPortPathQuery() {
        assertEquals("https://a.example.com:8443/x/y?z=1", ok("https://a.example.com:8443/x/y?z=1").url)
    }

    @Test fun rejectsMalformedAndDangerousInput() {
        bad("")
        bad("   ")
        bad("javascript:alert(1)")
        bad("file:///etc/passwd")
        bad("ftp://example.com")
        bad("intent://scan/#Intent;scheme=zxing;end")
        bad("https://user:pass@example.com")
        bad("not a url")
        bad("https://localhost")
        bad("https://exa_mple.com")
        bad("https://")
        bad("https://example.com:abc")
        bad("a".repeat(3000))
    }

    @Test fun duplicateKeyIgnoresSchemeCaseAndTrailingSlash() {
        assertEquals(UrlValidator.duplicateKey("https://Example.com/a/"), UrlValidator.duplicateKey("http://example.com/a"))
        assertNotEquals(UrlValidator.duplicateKey("https://example.com/a"), UrlValidator.duplicateKey("https://example.com/b"))
    }

    @Test fun parseHosts() {
        assertEquals(listOf("a.com", "b.org"), UrlValidator.parseHosts("A.com, b.org a.com"))
        assertNull(UrlValidator.parseHosts("a.com, bad host!"))
        assertEquals(emptyList<String>(), UrlValidator.parseHosts(""))
    }

    @Test fun logoutUrlMustBeHttps() {
        assertTrue(UrlValidator.normalizeLogoutUrl("") is UrlValidator.Result.Ok)
        assertTrue(UrlValidator.normalizeLogoutUrl("http://example.com/out") is UrlValidator.Result.Error)
        assertTrue(UrlValidator.normalizeLogoutUrl("example.com/out") is UrlValidator.Result.Ok)
    }
}
