package com.tabeer.travelhub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tabeer.travelhub.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(vm: AppViewModel, portalId: String?) {
    val existing = remember(portalId) { vm.portal(portalId) }
    val isEdit = existing != null
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var category by remember { mutableStateOf(existing?.category.orEmpty()) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    var iconText by remember { mutableStateOf(existing?.iconText.orEmpty()) }
    var color by remember { mutableStateOf(existing?.colorHex.orEmpty()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var openMode by remember { mutableStateOf(existing?.openMode ?: vm.settings.defaultOpenMode) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var autoFill by remember { mutableStateOf(existing?.autoFill ?: false) }
    var autoSubmit by remember { mutableStateOf(existing?.autoSubmit ?: false) }
    var rememberSession by remember { mutableStateOf(existing?.rememberSession ?: true) }
    var extraHosts by remember { mutableStateOf(existing?.extraTrustedHosts?.joinToString(", ").orEmpty()) }
    var logoutUrl by remember { mutableStateOf(existing?.logoutUrl.orEmpty()) }
    var allowHttp by remember { mutableStateOf(existing?.allowHttp ?: false) }

    var nameErr by remember { mutableStateOf<String?>(null) }
    var urlErr by remember { mutableStateOf<String?>(null) }
    var hostsErr by remember { mutableStateOf<String?>(null) }
    var logoutErr by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var httpWarning by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    val hasCreds = existing != null && vm.credentialIds.contains(existing.id)
    val unreadable = hasCreds && remember(existing) { vm.vault.get(existing!!.id) == null }
    val categorySuggestions = remember(vm.portals) { (PortalFilter.SUGGESTED_CATEGORIES + PortalFilter.categories(vm.portals)).distinctBy { it.lowercase() } }

    fun back() { vm.screen = Screen.Dashboard }

    fun doSave(httpConfirmed: Boolean) {
        nameErr = null; urlErr = null; hostsErr = null; logoutErr = null
        if (name.isBlank()) { nameErr = "Enter a portal name"; return }
        val norm = UrlValidator.normalize(url)
        if (norm is UrlValidator.Result.Error) { urlErr = norm.message; return }
        norm as UrlValidator.Result.Ok
        if (norm.isHttp && !httpConfirmed && !(allowHttp && existing?.url == norm.url)) { httpWarning = true; return }
        val hosts = UrlValidator.parseHosts(extraHosts)
        if (hosts == null) { hostsErr = "Use valid domain names separated by commas (e.g. sso.example.com)"; return }
        val lo = UrlValidator.normalizeLogoutUrl(logoutUrl)
        if (lo is UrlValidator.Result.Error) { logoutErr = lo.message; return }
        saving = true
        val draft = (existing ?: Portal(name = "", url = "")).copy(
            name = name, url = norm.url, category = category, notes = notes, iconText = iconText, colorHex = color,
            openMode = openMode, enabled = enabled, allowHttp = norm.isHttp, autoFill = autoFill,
            autoSubmit = autoFill && autoSubmit, rememberSession = rememberSession,
            extraTrustedHosts = hosts, logoutUrl = (lo as UrlValidator.Result.Ok).url,
        )
        vm.savePortal(draft, username.trim(), password) { err ->
            saving = false
            if (err == null) { password = ""; back() } else urlErr = err
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit portal" else "Add portal") },
                navigationIcon = { IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        modifier = Modifier.imePadding(),
    ) { inner ->
        Column(
            Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(Portal.MAX_NAME) }, label = { Text("Portal name *") },
                singleLine = true, isError = nameErr != null, supportingText = nameErr?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("Website address *") },
                placeholder = { Text("https://example.com/login") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = urlErr != null, supportingText = { Text(urlErr ?: "https:// is added automatically if you leave it out") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = category, onValueChange = { category = it.take(40) }, label = { Text("Category (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categorySuggestions) { c -> AssistChip(onClick = { category = c }, label = { Text(c) }) }
            }
            OutlinedTextField(
                value = notes, onValueChange = { notes = it.take(Portal.MAX_NOTES) }, label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = iconText, onValueChange = { iconText = it.take(2) }, label = { Text("Icon text") },
                    singleLine = true, modifier = Modifier.width(120.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { ColorDot(null, color.isEmpty()) { color = "" } }
                    items(PortalPalette) { hex -> ColorDot(hex, color.equals(hex, true)) { color = hex } }
                }
            }

            SectionTitle("Login (stored encrypted on this device)")
            if (hasCreds) {
                Text(
                    if (unreadable) "⚠ A saved login exists but can't be decrypted on this device. Enter it again to replace it."
                    else "🔒 A login is saved. Saved details are never shown. Leave these blank to keep them, or type new values to replace them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unreadable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = username, onValueChange = { username = it }, label = { Text("Username (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
            )
            if (hasCreds) {
                OutlinedButton(onClick = { confirmForget = true }) { Text("Forget saved login") }
            }

            SectionTitle("Opening & automatic login")
            Text("Open mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = openMode == OpenMode.IN_APP, onClick = { openMode = OpenMode.IN_APP }, label = { Text("In TravelHub") })
                FilterChip(selected = openMode == OpenMode.EXTERNAL, onClick = { openMode = OpenMode.EXTERNAL }, label = { Text("External browser") })
            }
            SwitchRow("Enabled", "Disabled portals are hidden from the dashboard", enabled) { enabled = it }
            SwitchRow(
                "Fill saved login automatically",
                "Fills username/password on the trusted site's login page. Never works through CAPTCHA/OTP; you complete those.",
                autoFill,
            ) { autoFill = it; if (!it) autoSubmit = false }
            SwitchRow(
                "Also press the sign-in button",
                "Submits once per visit. Off = you tap the site's own button.",
                autoSubmit, enabled = autoFill,
            ) { autoSubmit = it }
            SwitchRow("Keep me signed in (remember session)", "Off = this portal's cookies are cleared when you leave it", rememberSession) { rememberSession = it }
            OutlinedTextField(
                value = extraHosts, onValueChange = { extraHosts = it }, label = { Text("Extra trusted domains (optional)") },
                placeholder = { Text("sso.example.com, login.example.com") },
                isError = hostsErr != null,
                supportingText = { Text(hostsErr ?: "Logins are only ever filled on this portal's own domain (and its subdomains) plus the domains listed here.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = logoutUrl, onValueChange = { logoutUrl = it }, label = { Text("Sign-out address (optional)") },
                singleLine = true, isError = logoutErr != null,
                supportingText = { Text(logoutErr ?: "Used by “Sign out” in the browser menu") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (isEdit) {
                TextButton(onClick = {
                    autoFill = false; autoSubmit = false; extraHosts = ""; logoutUrl = ""
                    vm.toast("Login settings reset. Tap Save to apply.")
                }) { Text("Reset login settings") }
            }

            Button(onClick = { doSave(false) }, enabled = !saving, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (isEdit) "Save changes" else "Add portal")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (httpWarning) {
        AlertDialog(
            onDismissRequest = { httpWarning = false },
            icon = { Text("⚠️") },
            title = { Text("This address isn't secure") },
            text = {
                Text(
                    "http:// sites send everything — including your password — without encryption, so anyone on the same " +
                        "network may be able to read it. Use the https:// address if the site supports it. " +
                        "Only continue if you understand the risk."
                )
            },
            confirmButton = { TextButton(onClick = { httpWarning = false; allowHttp = true; doSave(true) }) { Text("Allow http for this portal", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { httpWarning = false }) { Text("Cancel") } },
        )
    }
    if (confirmForget && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget saved login?") },
            text = { Text("The username and password for this portal are deleted from this device. The portal stays.") },
            confirmButton = { TextButton(onClick = { vm.forgetCredentials(existing.id); confirmForget = false }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ColorDot(hex: String?, selected: Boolean, onClick: () -> Unit) {
    val c = hex?.let { parseColor(it) }
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(c ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (hex == null) Text("A", style = MaterialTheme.typography.labelSmall) }
}
