package com.tabeer.travelhub.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tabeer.travelhub.browser.LoginScript
import com.tabeer.travelhub.browser.NavigationPolicy
import com.tabeer.travelhub.browser.WebData
import com.tabeer.travelhub.data.Portal
import com.tabeer.travelhub.data.TrustedDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the WebView and its state for one portal visit.
 * Security properties: no JavaScript bridge is ever registered; SSL errors always cancel; mixed
 * content is blocked; file/content access is off; WebView permission prompts (camera, mic,
 * geolocation) are denied; credentials are decrypted only at the moment of filling and are only
 * injected after the CURRENT page's origin is verified against the portal's trusted hosts.
 */
class BrowserController(
    private val context: Context,
    private val vm: AppViewModel,
    private val portal: Portal,
    private val scope: CoroutineScope,
) {
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(true)
    var title by mutableStateOf("")
    var url by mutableStateOf(portal.url)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var crashed by mutableStateOf(false)

    var chooserCallback: ValueCallback<Array<Uri>>? = null
    var launchChooser: (Intent) -> Boolean = { false }

    private var autoAttempted = false
    private var destroyed = false

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    private fun trusted(u: String?) = TrustedDomain.isTrusted(u, portal.trustedHosts, portal.allowHttp)

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(context)
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.settings.javaScriptEnabled = true // needed by modern portals; navigation is restricted by NavigationPolicy
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        wv.settings.javaScriptCanOpenWindowsAutomatically = false
        wv.settings.setSupportMultipleWindows(false)
        wv.settings.setGeolocationEnabled(false)
        wv.settings.setSafeBrowsingEnabled(true)
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return when (val d = NavigationPolicy.decide(request.url.toString(), portal.allowHttp, portal.trustedHosts)) {
                    NavigationPolicy.Decision.Allow -> false
                    is NavigationPolicy.Decision.Redirect -> { view.loadUrl(d.url); true }
                    is NavigationPolicy.Decision.External -> {
                        if (request.hasGesture()) openExternalScheme(d.url)
                        true
                    }
                    is NavigationPolicy.Decision.Block -> { if (request.isForMainFrame) toast(d.reason); true }
                }
            }

            override fun onPageStarted(view: WebView, pageUrl: String, favicon: android.graphics.Bitmap?) {
                loading = true; error = null; url = pageUrl
            }

            override fun onPageFinished(view: WebView, pageUrl: String) {
                loading = false; url = pageUrl
                canBack = view.canGoBack(); canForward = view.canGoForward()
                maybeAutoFill(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                if (request.isForMainFrame) error = "Couldn't load this page: ${err.description}"
            }

            // Never proceed through certificate errors, ever.
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, e: android.net.http.SslError) {
                handler.cancel()
                error = "The site's security certificate is not valid, so the page was not loaded."
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                crashed = true
                error = "The page stopped responding. Go back and open the portal again."
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
            // Deny camera/microphone/etc. requests from pages.
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooserCallback?.onReceiveValue(null)
                chooserCallback = cb
                val ok = try { launchChooser(params.createIntent()) } catch (e: Exception) { false }
                if (!ok) { chooserCallback = null; cb.onReceiveValue(null) }
                return ok
            }
        }

        wv.setDownloadListener { dlUrl, ua, cd, mime, _ -> startDownload(dlUrl, ua, cd, mime) }
        return wv
    }

    val webView: WebView = buildWebView()

    fun load() = webView.loadUrl(portal.url)

    fun reload() { error = null; webView.reload() }
    fun stop() { webView.stopLoading(); loading = false }
    fun goBack() = webView.goBack()
    fun goForward() = webView.goForward()
    fun handleBack(): Boolean = if (webView.canGoBack()) { webView.goBack(); true } else false

    fun signOut() {
        if (portal.logoutUrl.isBlank()) toast("No sign-out address set. Add one in the portal's settings.")
        else webView.loadUrl(portal.logoutUrl)
    }

    fun openExternally() = openExternal(context, webView.url ?: portal.url)

    private fun openExternalScheme(u: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (e: Exception) { toast("No app can open this link") }
    }

    private fun startDownload(dlUrl: String, ua: String?, cd: String?, mime: String?) {
        val scheme = Uri.parse(dlUrl).scheme?.lowercase()
        if (scheme != "https" && !(scheme == "http" && portal.allowHttp)) { toast("This download type isn't supported"); return }
        try {
            val name = URLUtil.guessFileName(dlUrl, cd, mime)
            val req = DownloadManager.Request(Uri.parse(dlUrl))
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(dlUrl)?.let { req.addRequestHeader("Cookie", it) }
            ua?.let { req.addRequestHeader("User-Agent", it) }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) { toast("Download failed") }
    }

    /** Fills the login form if (and only if) the current page is on a trusted host. */
    fun fill(submit: Boolean, manual: Boolean) {
        if (!trusted(webView.url)) {
            if (manual) toast("This page isn't on the portal's trusted domain, so your login was not filled.")
            return
        }
        scope.launch {
            val creds = withContext(Dispatchers.IO) { vm.vault.get(portal.id) }
            if (creds == null) { if (manual) toast("No saved login for this portal (or it can't be decrypted)."); return@launch }
            if (destroyed || !trusted(webView.url)) return@launch // re-verify right before injecting
            webView.evaluateJavascript(LoginScript.fill(creds.username, creds.password, submit)) { r ->
                if (manual) toast(
                    when (r?.trim('"')) {
                        "filled" -> "Login filled. Complete any CAPTCHA/OTP, then sign in."
                        "submitted" -> "Login submitted"
                        "nofield" -> "No login form found on this page"
                        else -> "Couldn't fill this page"
                    }
                )
            }
        }
    }

    private fun maybeAutoFill(view: WebView) {
        if (!portal.autoFill || autoAttempted || destroyed || !trusted(view.url)) return
        view.postDelayed({
            if (destroyed || autoAttempted || !trusted(view.url)) return@postDelayed
            view.evaluateJavascript(LoginScript.DETECT) { r ->
                // One automatic attempt per visit: a failed login can never turn into a retry loop.
                if (r == "true" && !autoAttempted && !destroyed && trusted(view.url)) {
                    autoAttempted = true
                    fill(portal.autoSubmit, manual = false)
                }
            }
        }, 800)
    }

    fun onPause() = webView.onPause()
    fun onResume() = webView.onResume()

    fun destroy() {
        destroyed = true
        chooserCallback?.onReceiveValue(null)
        chooserCallback = null
        webView.stopLoading()
        webView.destroy()
        CookieManager.getInstance().flush()
        if (!portal.rememberSession) WebData.clearPortal(portal)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: AppViewModel, portal: Portal) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(portal.id) { BrowserController(context, vm, portal, scope) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val hasLogin = vm.credentialIds.contains(portal.id)

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        controller.chooserCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        controller.chooserCallback = null
    }
    controller.launchChooser = { intent ->
        vm.externalActivityInFlight = true
        chooser.launch(intent)
        true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); controller.destroy() }
    }
    LaunchedEffect(controller) { controller.load() }

    fun close() { vm.screen = Screen.Dashboard }
    BackHandler { if (!controller.handleBack()) close() }

    val host = remember(controller.url) { com.tabeer.travelhub.data.UrlValidator.hostOf(controller.url).orEmpty() }
    val secure = controller.url.startsWith("https://")

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, "Close portal") } },
                    title = {
                        Column {
                            Text(controller.title.ifBlank { portal.name }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (secure) "🔒 " else "⚠ not secure · ") + host,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (secure) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )
                if (controller.loading) LinearProgressIndicator(progress = { controller.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            BottomAppBar {
                IconButton(onClick = { controller.goBack() }, enabled = controller.canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton(onClick = { controller.goForward() }, enabled = controller.canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                if (controller.loading) IconButton(onClick = { controller.stop() }) { Icon(Icons.Filled.Close, "Stop loading") }
                else IconButton(onClick = { controller.reload() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                IconButton(onClick = { controller.fill(submit = false, manual = true) }, enabled = hasLogin) { Icon(Icons.Filled.Person, "Fill saved login") }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Fill login and sign in") }, enabled = hasLogin, onClick = { menu = false; controller.fill(submit = true, manual = true) })
                        DropdownMenuItem(text = { Text("Open in external browser") }, onClick = { menu = false; controller.openExternally() })
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; controller.signOut() })
                        DropdownMenuItem(text = { Text("Clear this portal's session") }, onClick = { menu = false; confirmClear = true })
                        DropdownMenuItem(text = { Text("Edit portal settings") }, onClick = { menu = false; vm.screen = Screen.Form(portal.id) })
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            AndroidView(factory = { controller.webView }, modifier = Modifier.fillMaxSize())
            controller.error?.let { msg ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("Can't open this page", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        if (!controller.crashed) Button(onClick = { controller.reload() }) { Text("Retry") }
                        OutlinedButton(onClick = { controller.openExternally() }) { Text("Open in external browser") }
                        TextButton(onClick = { close() }) { Text("Close") }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear this portal's session?") },
            text = { Text("You'll be signed out. Best-effort: WebView shares one cookie store across portals.") },
            confirmButton = { TextButton(onClick = { WebData.clearPortal(portal); controller.reload(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
