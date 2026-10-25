package com.tabeer.travelhub.browser

import com.tabeer.travelhub.data.TrustedDomain
import java.net.URI
import java.net.URLDecoder

/**
 * Decides what the in-app browser may do with a navigation request. Pure and unit-tested.
 *  - https:// pages (including redirects to other hosts, e.g. payment gateways) load in the WebView,
 *    but saved credentials are only ever filled on trusted hosts (see TrustedDomain).
 *  - http:// loads only for a portal that was explicitly opted in, and only on its trusted hosts.
 *  - tel:/mailto:/sms: are handed to the system only on a user gesture.
 *  - intent:// is never executed; only an https browser_fallback_url is followed.
 *  - file:, content:, javascript:, data:, ftp:, custom schemes: blocked.
 */
object NavigationPolicy {
    sealed class Decision {
        data object Allow : Decision()
        data class Redirect(val url: String) : Decision()
        data class External(val url: String) : Decision()
        data class Block(val reason: String) : Decision()
    }

    private val FALLBACK = Regex("S\\.browser_fallback_url=([^;]+)")

    fun decide(url: String, allowHttp: Boolean, trustedHosts: List<String>, depth: Int = 0): Decision {
        val uri = try { URI(url.trim()) } catch (e: Exception) { return Decision.Block("Invalid address") }
        return when (uri.scheme?.lowercase()) {
            "https" -> Decision.Allow
            "http" ->
                if (allowHttp && TrustedDomain.isTrusted(url, trustedHosts, true)) Decision.Allow
                else Decision.Block("Blocked an insecure http:// page")
            "about" -> if (url.equals("about:blank", ignoreCase = true)) Decision.Allow else Decision.Block("Blocked")
            "tel", "mailto", "sms" -> Decision.External(url)
            "intent" -> {
                val fallback = FALLBACK.find(url)?.groupValues?.get(1)?.let {
                    try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { null }
                }
                if (fallback != null && depth == 0 && decide(fallback, allowHttp, trustedHosts, 1) == Decision.Allow &&
                    fallback.startsWith("https://", ignoreCase = true)
                ) Decision.Redirect(fallback)
                else Decision.Block("Blocked an app-launch link")
            }
            else -> Decision.Block("Blocked an unsupported link type")
        }
    }
}

/** Builds the small scripts used for form filling. Values are JSON-quoted, never concatenated raw. */
object LoginScript {
    /** Returns "true" if a visible password field exists. */
    const val DETECT = """(function(){try{var l=document.querySelectorAll('input[type=password]');for(var i=0;i<l.length;i++){var r=l[i].getBoundingClientRect();if(r.width>0&&r.height>0&&!l[i].disabled){return true;}}return false;}catch(e){return false;}})()"""

    private const val FILL_BODY = """
(function(u,p,submit){
 try{
  function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&!e.disabled&&!e.readOnly;}
  var pw=Array.prototype.slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];
  if(!pw){return 'nofield';}
  var scope=pw.form||document;
  var cands=Array.prototype.slice.call(scope.querySelectorAll('input')).filter(function(e){var t=(e.type||'text').toLowerCase();return vis(e)&&['text','email','tel','number'].indexOf(t)>=0;});
  var user=null;
  for(var i=0;i<cands.length;i++){if(cands[i].compareDocumentPosition(pw)&Node.DOCUMENT_POSITION_FOLLOWING){user=cands[i];}}
  function setv(e,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
  if(user&&u){setv(user,u);}
  setv(pw,p);
  if(submit){
   var f=pw.form;var b=f&&f.querySelector('button[type=submit],input[type=submit]');
   if(b){b.click();return 'submitted';}
   if(f&&f.requestSubmit){f.requestSubmit();return 'submitted';}
   return 'filled';
  }
  return 'filled';
 }catch(e){return 'error';}
})"""

    fun fill(username: String, password: String, submit: Boolean): String =
        FILL_BODY + "(" + quote(username) + "," + quote(password) + "," + submit + ")"

    /** JSON string literal (also valid JS), escaping quotes, backslashes, control chars and U+2028/2029. */
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch == '<' || ch == '>' || ch == '&' || ch.code < 0x20 || ch == ' ' || ch == ' ' ->
                sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }
}
