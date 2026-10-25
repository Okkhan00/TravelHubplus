package com.tabeer.travelhub.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.tabeer.travelhub.data.Portal

/**
 * Website data clearing.
 * NOTE: Android's WebView uses ONE cookie store for the whole app, so portals are not cryptographically
 * or structurally isolated from each other. Per-portal clearing is best-effort: it expires the cookies
 * visible for the portal's trusted hosts (cookies set with unusual Domain/Path attributes may remain).
 */
object WebData {
    fun clearPortal(portal: Portal) {
        val cm = CookieManager.getInstance()
        val storage = WebStorage.getInstance()
        for (host in portal.trustedHosts) {
            for (scheme in listOf("https", "http")) {
                val url = "$scheme://$host/"
                cm.getCookie(url)?.split(";")?.forEach { c ->
                    val name = c.substringBefore("=").trim()
                    if (name.isNotEmpty()) {
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$host")
                    }
                }
            }
            storage.deleteOrigin("https://$host")
            storage.deleteOrigin("http://$host")
        }
        cm.flush()
    }

    /** Clears cookies, DOM storage, cache, form data and HTTP-auth data for every site. */
    fun clearAll(context: Context) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        WebStorage.getInstance().deleteAllData()
        try {
            android.webkit.WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
            val wv = WebView(context)
            wv.clearCache(true)
            wv.clearFormData()
            wv.destroy()
        } catch (e: Exception) {
            // best effort; never log site data
        }
    }
}
