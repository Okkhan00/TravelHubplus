package com.tabeer.travelhub.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.tabeer.travelhub.data.*
import com.tabeer.travelhub.security.BiometricHelper
import com.tabeer.travelhub.security.PinManager

private enum class PinDialogKind { SET, CHANGE, REMOVE }

private val LOCK_OPTIONS = listOf("Immediately" to 0, "After 30 seconds" to 30, "After 1 minute" to 60, "After 5 minutes" to 300, "Never (only manual lock)" to -1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, activity: FragmentActivity) {
    val context = LocalContext.current
    val s = vm.settings
    var pinDialog by remember { mutableStateOf<PinDialogKind?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var authAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) } // which info dialog
    var confirm by remember { mutableStateOf<String?>(null) } // which destructive confirm
    var lockMenu by remember { mutableStateOf(false) }
    val bioAvailable = remember { BiometricHelper.isAvailable(context) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.writeExport(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importFrom(it) }
    }

    /** Runs [action] immediately if no PIN is set, otherwise after the user re-enters their PIN (or biometric). */
    fun withAuth(action: () -> Unit) {
        if (!vm.pinSet) action() else { authError = null; authAction = action }
    }

    fun lockErr(r: PinManager.VerifyResult): String? = when (r) {
        is PinManager.VerifyResult.Success -> null
        is PinManager.VerifyResult.Wrong -> "Wrong PIN"
        is PinManager.VerifyResult.LockedOut -> "Too many attempts. Try again at ${formatTime(r.untilMs)}."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { vm.screen = Screen.Dashboard }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).verticalScroll(rememberScrollState())) {
            SectionTitle("Security")
            if (!vm.pinSet) {
                ListItem(
                    headlineContent = { Text("Set up app lock") },
                    supportingContent = { Text("Protect TravelHub with a PIN. Optional fingerprint/face unlock needs a PIN as fallback.") },
                    modifier = Modifier.clickable { pinError = null; pinDialog = PinDialogKind.SET },
                )
            } else {
                ListItem(headlineContent = { Text("Change PIN") }, modifier = Modifier.clickable { pinError = null; pinDialog = PinDialogKind.CHANGE })
                ListItem(headlineContent = { Text("Remove PIN") }, modifier = Modifier.clickable { pinError = null; pinDialog = PinDialogKind.REMOVE })
                SwitchRow(
                    "Fingerprint / face unlock",
                    if (bioAvailable) "Strong biometrics only; your PIN always works as a fallback" else "No enrolled strong biometrics on this device",
                    s.biometricEnabled, enabled = bioAvailable,
                ) { on -> vm.updateSettings { it.copy(biometricEnabled = on) } }
                Box {
                    ListItem(
                        headlineContent = { Text("Auto-lock") },
                        supportingContent = { Text(LOCK_OPTIONS.firstOrNull { it.second == s.autoLockSeconds }?.first ?: "After ${s.autoLockSeconds}s") },
                        modifier = Modifier.clickable { lockMenu = true },
                    )
                    DropdownMenu(expanded = lockMenu, onDismissRequest = { lockMenu = false }) {
                        LOCK_OPTIONS.forEach { (label, v) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { lockMenu = false; vm.updateSettings { it.copy(autoLockSeconds = v) } })
                        }
                    }
                }
            }
            SwitchRow(
                "Hide app content from screenshots & recent apps",
                "Blocks screenshots and blanks the app preview in the task switcher",
                s.blockScreenshots,
            ) { on -> vm.updateSettings { it.copy(blockScreenshots = on) } }

            SectionTitle("Appearance & browsing")
            ChoiceRow("Theme", ThemeMode.entries, s.theme, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { v -> vm.updateSettings { it.copy(theme = v) } }
            ChoiceRow("Default portal view", ViewMode.entries, s.view, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { v -> vm.updateSettings { it.copy(view = v) } }
            ChoiceRow("Open new portals in", OpenMode.entries, s.defaultOpenMode, { if (it == OpenMode.IN_APP) "TravelHub browser" else "External browser" }) { v -> vm.updateSettings { it.copy(defaultOpenMode = v) } }
            ListItem(
                headlineContent = { Text("Clear all browsing data") },
                supportingContent = { Text("Signs you out of every portal and clears cookies, cache and site data") },
                modifier = Modifier.clickable { confirm = "browsing" },
            )

            SectionTitle("Backup & restore")
            ListItem(
                headlineContent = { Text("Export portal list") },
                supportingContent = { Text("Saves names, addresses and categories. NO usernames or passwords are included, so restoring will not restore logins.") },
                modifier = Modifier.clickable { withAuth { vm.externalActivityInFlight = true; exportLauncher.launch("travelhub-portals.json") } },
            )
            ListItem(
                headlineContent = { Text("Import portal list") },
                supportingContent = { Text("Adds portals from a TravelHub export. Duplicates and insecure entries are skipped; login automation is not restored.") },
                modifier = Modifier.clickable { vm.externalActivityInFlight = true; importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )

            SectionTitle("Privacy & about")
            ListItem(headlineContent = { Text("Privacy information") }, modifier = Modifier.clickable { info = "privacy" })
            ListItem(headlineContent = { Text("Diagnostics (no secrets)") }, modifier = Modifier.clickable { info = "diag" })
            ListItem(headlineContent = { Text("Open-source licenses") }, modifier = Modifier.clickable { info = "licenses" })
            ListItem(
                headlineContent = { Text("About TravelHub") },
                supportingContent = { Text("Version ${appVersion(context)}") },
                modifier = Modifier.clickable { info = "about" },
            )

            SectionTitle("Reset")
            ListItem(
                headlineContent = { Text("Delete all saved logins") },
                supportingContent = { Text("Keeps your portals, removes every stored username and password") },
                modifier = Modifier.clickable { withAuth { confirm = "creds" } },
            )
            ListItem(
                headlineContent = { Text("Delete everything", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("Removes all portals, saved logins and browsing data from this device") },
                modifier = Modifier.clickable { withAuth { confirm = "all" } },
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    // ---- PIN dialogs ----
    when (pinDialog) {
        PinDialogKind.SET -> PinEntryDialog(
            "Set a PIN", listOf("New PIN (4–12 digits)", "Confirm PIN"), "Save", pinError, { pinDialog = null },
        ) { v ->
            if (!vm.isValidPin(v[0])) pinError = "Use 4 to 12 digits"
            else if (v[0] != v[1]) pinError = "PINs don't match"
            else vm.setPin(v[0]) { ok -> if (ok) { pinDialog = null; vm.toast("App lock enabled") } else pinError = "Couldn't save PIN" }
        }
        PinDialogKind.CHANGE -> PinEntryDialog(
            "Change PIN", listOf("Current PIN", "New PIN (4–12 digits)", "Confirm new PIN"), "Change", pinError, { pinDialog = null },
        ) { v ->
            if (!vm.isValidPin(v[1])) pinError = "Use 4 to 12 digits"
            else if (v[1] != v[2]) pinError = "New PINs don't match"
            else vm.changePin(v[0], v[1]) { r -> pinError = lockErr(r); if (r is PinManager.VerifyResult.Success) { pinDialog = null; vm.toast("PIN changed") } }
        }
        PinDialogKind.REMOVE -> PinEntryDialog(
            "Remove PIN", listOf("Current PIN"), "Remove", pinError, { pinDialog = null },
        ) { v ->
            vm.removePin(v[0]) { r -> pinError = lockErr(r); if (r is PinManager.VerifyResult.Success) { pinDialog = null; vm.toast("App lock removed") } }
        }
        null -> {}
    }

    // ---- re-authentication before sensitive actions ----
    authAction?.let { action ->
        PinEntryDialog(
            "Enter your PIN to continue", listOf("PIN"), "Continue", authError, { authAction = null },
            extra = if (s.biometricEnabled && bioAvailable) {
                { TextButton(onClick = {
                    BiometricHelper.prompt(activity, "Confirm it's you", onSuccess = { authAction = null; action() }, onFailure = { })
                }) { Text("Use fingerprint / face") } }
            } else null,
        ) { v ->
            vm.verifyForAction(v[0]) { r ->
                authError = lockErr(r)
                if (r is PinManager.VerifyResult.Success) { authAction = null; action() }
            }
        }
    }

    // ---- destructive confirmations ----
    confirm?.let { which ->
        val (title, body, run) = when (which) {
            "browsing" -> Triple("Clear all browsing data?", "You'll be signed out of every portal. Portals and saved logins are kept.") { vm.clearBrowsingData() }
            "creds" -> Triple("Delete all saved logins?", "Every stored username and password is permanently deleted. Your portal list is kept.") { vm.deleteAllCredentials() }
            else -> Triple("Delete everything?", "All portals, saved logins and browsing data are permanently removed from this device. This can't be undone.") { vm.deleteEverything() }
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { run(); confirm = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    // ---- info dialogs ----
    info?.let { which ->
        val (title, body) = when (which) {
            "privacy" -> "Privacy" to PRIVACY_TEXT
            "diag" -> "Diagnostics" to diagnostics(vm, context)
            "licenses" -> "Open-source licenses" to LICENSES_TEXT
            else -> "About TravelHub" to "TravelHub ${appVersion(context)}\n\nA personal hub for your own travel-agent portals. No account, no server, no ads, no analytics.\n\nPackage: ${context.packageName}"
        }
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(body, style = MaterialTheme.typography.bodyMedium) } },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(label(selected)) }, modifier = Modifier.clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; onSelect(o) }) }
        }
    }
}

private fun appVersion(context: android.content.Context): String = try {
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    "${pi.versionName} (build ${pi.longVersionCode})"
} catch (e: Exception) { "unknown" }

private fun diagnostics(vm: AppViewModel, context: android.content.Context): String = buildString {
    appendLine("App: ${appVersion(context)}")
    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Portals: ${vm.portals.size}")
    appendLine("Saved logins: ${vm.credentialIds.size}")
    appendLine("App lock: ${if (vm.pinSet) "on" else "off"}")
    appendLine("Biometric unlock: ${if (vm.settings.biometricEnabled) "on" else "off"}")
    appendLine("Strong biometrics available: ${BiometricHelper.isAvailable(context)}")
    appendLine("Screenshot blocking: ${if (vm.settings.blockScreenshots) "on" else "off"}")
    append("No usernames, passwords, URLs or cookies are included here.")
}

private const val PRIVACY_TEXT = """What TravelHub stores (only on this device):
• Your portal list (names, addresses, categories, notes) — plain app-private file.
• Usernames and passwords — encrypted with AES-256-GCM using a key held in the Android Keystore, in a separate file.
• Your PIN as a salted PBKDF2 hash (never the PIN itself).
• Website cookies/sessions, kept by Android's WebView exactly like a browser would.

What it never does:
• No account, server, analytics, ads or tracking. Nothing is uploaded.
• Passwords are never exported, logged or shown.
• Saved logins are only filled on the portal's own trusted domain.

Limits you should know:
• Android's WebView shares a single cookie store across all portals; per-portal clearing is best-effort.
• The app lock protects the app's screens, not a rooted/compromised phone. The encryption key lives in the Keystore and isn't exportable, but code running inside this app can use it.
• Anyone you give an unlocked phone to can use sessions you left signed in."""

private const val LICENSES_TEXT = """TravelHub is built with these open-source components, all licensed under the Apache License 2.0:

• AndroidX (core, activity, fragment, lifecycle, biometric)
• Jetpack Compose and Material 3
• Kotlin and kotlinx.coroutines

Full license text: https://www.apache.org/licenses/LICENSE-2.0"""
