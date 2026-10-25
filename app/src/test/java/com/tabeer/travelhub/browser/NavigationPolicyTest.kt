package com.tabeer.travelhub.browser

import com.tabeer.travelhub.browser.NavigationPolicy.Decision
import org.junit.Assert.*
import org.junit.Test

class NavigationPolicyTest {
    private val hosts = listOf("portal.example.com")
    private fun d(url: String, allowHttp: Boolean = false) = NavigationPolicy.decide(url, allowHttp, hosts)

    @Test fun httpsAllowed() = assertEquals(Decision.Allow, d("https://pay.gateway.net/x"))

    @Test fun httpBlockedUnlessOptedInAndTrusted() {
        assertTrue(d("http://portal.example.com/") is Decision.Block)
        assertEquals(Decision.Allow, d("http://portal.example.com/", allowHttp = true))
        assertTrue(d("http://other.net/", allowHttp = true) is Decision.Block)
    }

    @Test fun dangerousSchemesBlocked() {
        listOf("file:///sdcard/x", "content://media/x", "javascript:alert(1)", "data:text/html,hi", "ftp://x.com", "market://details?id=a", "blob:https://x.com/1")
            .forEach { assertTrue(it, d(it) is Decision.Block) }
    }

    @Test fun telMailtoSmsGoExternal() {
        assertTrue(d("tel:+123456") is Decision.External)
        assertTrue(d("mailto:a@b.com") is Decision.External)
        assertTrue(d("sms:+123") is Decision.External)
    }

    @Test fun intentSchemeNeverExecutedOnlyHttpsFallbackFollowed() {
        assertTrue(d("intent://scan/#Intent;scheme=zxing;package=com.x;end") is Decision.Block)
        assertEquals(
            Decision.Redirect("https://example.com/app"),
            d("intent://x#Intent;scheme=a;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fapp;end"),
        )
        assertTrue(d("intent://x#Intent;scheme=a;S.browser_fallback_url=http%3A%2F%2Fexample.com;end") is Decision.Block)
        assertTrue(d("intent://x#Intent;scheme=a;S.browser_fallback_url=file%3A%2F%2F%2Fetc;end") is Decision.Block)
    }

    @Test fun aboutBlankOnly() {
        assertEquals(Decision.Allow, d("about:blank"))
        assertTrue(d("about:config") is Decision.Block)
    }
}

class LoginScriptTest {
    @Test fun quoteEscapesInjectionAttempts() {
        val q = LoginScript.quote("\"; alert(1); //\\ \n </script>  ")
        assertFalse(q.contains("\n"))
        assertFalse(q.contains("</script>"))
        assertFalse(q.contains(' '))
        assertTrue(q.startsWith("\"") && q.endsWith("\""))
        // every inner double quote is escaped
        assertEquals(0, Regex("(?<!\\\\)\"").findAll(q.substring(1, q.length - 1)).count())
    }

    @Test fun fillScriptEmbedsQuotedValuesOnly() {
        val s = LoginScript.fill("user'\"x", "p\\w", true)
        assertTrue(s.endsWith("(\"user'\\\"x\",\"p\\\\w\",true)"))
    }
}
