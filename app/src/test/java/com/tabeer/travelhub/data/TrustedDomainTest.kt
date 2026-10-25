package com.tabeer.travelhub.data

import org.junit.Assert.*
import org.junit.Test

class TrustedDomainTest {
    private val hosts = listOf("portal.example.com")

    @Test fun exactAndSubdomainTrusted() {
        assertTrue(TrustedDomain.isTrusted("https://portal.example.com/login", hosts, false))
        assertTrue(TrustedDomain.isTrusted("https://sso.portal.example.com/", hosts, false))
    }

    @Test fun lookAlikeAndParentDomainsRejected() {
        assertFalse(TrustedDomain.isTrusted("https://evilportal.example.com/", hosts, false))
        assertFalse(TrustedDomain.isTrusted("https://portal.example.com.evil.net/", hosts, false))
        assertFalse(TrustedDomain.isTrusted("https://example.com/", hosts, false))
        assertFalse(TrustedDomain.isTrusted("https://evil.net/?u=portal.example.com", hosts, false))
    }

    @Test fun userInfoTrickRejected() {
        assertFalse(TrustedDomain.isTrusted("https://portal.example.com@evil.net/", hosts, false))
    }

    @Test fun httpOnlyWithExplicitOptIn() {
        assertFalse(TrustedDomain.isTrusted("http://portal.example.com/", hosts, false))
        assertTrue(TrustedDomain.isTrusted("http://portal.example.com/", hosts, true))
    }

    @Test fun otherSchemesAndNullRejected() {
        assertFalse(TrustedDomain.isTrusted(null, hosts, true))
        assertFalse(TrustedDomain.isTrusted("javascript:alert(1)", hosts, true))
        assertFalse(TrustedDomain.isTrusted("file://portal.example.com/x", hosts, true))
    }
}
