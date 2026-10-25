package com.tabeer.travelhub.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabeer.travelhub.browser.WebData
import com.tabeer.travelhub.data.*
import com.tabeer.travelhub.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

sealed interface Screen {
    data object Dashboard : Screen
    data class Form(val portalId: String?) : Screen
    data class Browser(val portalId: String) : Screen
    data object Settings : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val settingsStore = SettingsStore(ctx.getSharedPreferences("travelhub_settings", Context.MODE_PRIVATE))
    private val pin = PinManager(PrefsKv(ctx.getSharedPreferences("travelhub_lock", Context.MODE_PRIVATE)))

    val repo = PortalRepository(File(ctx.filesDir, "portals.json"))
    val vault = CredentialStore(File(ctx.filesDir, "vault.json"), AesGcmCipher(AndroidKeystoreKeyProvider()))

    var portals by mutableStateOf<List<Portal>>(emptyList()); private set
    var credentialIds by mutableStateOf<Set<String>>(emptySet()); private set
    var settings by mutableStateOf(AppSettings()); private set
    var pinSet by mutableStateOf(false); private set
    var locked by mutableStateOf(false); private set
    var screen by mutableStateOf<Screen>(Screen.Dashboard)
    var message by mutableStateOf<String?>(null)

    // dashboard filter state
    var query by mutableStateOf("")
    var categoryFilter by mutableStateOf<String?>(null)
    var favoritesOnly by mutableStateOf(false)

    /** Set before launching a system picker / chooser so returning from it doesn't trigger auto-lock. */
    var externalActivityInFlight = false
    private var backgroundedAt = 0L

    init {
        settings = settingsStore.load()
        pinSet = pin.isSet()
        locked = pinSet
        refresh()
    }

    private fun refresh() {
        portals = repo.all()
        credentialIds = vault.ids()
    }

    fun toast(msg: String) { message = msg }

    // ---------------- portals ----------------

    fun portal(id: String?): Portal? = portals.firstOrNull { it.id == id }

    /**
     * Saves a portal and (optionally) its credentials. Blank username/password fields mean
     * "keep what is stored" — editing never overwrites credentials with blanks.
     */
    fun savePortal(draft: Portal, username: String, password: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val old = if (draft.id.isNotEmpty()) repo.get(draft.id) else null
                val res = if (draft.id.isEmpty()) repo.add(draft) else repo.update(draft)
                if (res is PortalRepository.SaveResult.Saved) {
                    val id = res.portal.id
                    val hostChanged = old != null && old.host != res.portal.host
                    var note: String? = null
                    if (hostChanged && vault.has(id)) {
                        // Safety: never silently re-point saved credentials at a different website.
                        vault.delete(id)
                        note = "Website changed: saved credentials were removed for safety."
                    }
                    if (username.isNotEmpty() || password.isNotEmpty()) {
                        val existing = if (hostChanged) null else vault.get(id)
                        val ok = vault.save(
                            id,
                            Credentials(
                                username.ifEmpty { existing?.username.orEmpty() },
                                password.ifEmpty { existing?.password.orEmpty() },
                            ),
                        )
                        if (!ok) note = "Portal saved, but the login could not be stored securely."
                    }
                    res to note
                } else res to null
            }
            refresh()
            when (val r = result.first) {
                is PortalRepository.SaveResult.Saved -> { result.second?.let { toast(it) }; onDone(null) }
                is PortalRepository.SaveResult.Duplicate -> onDone("A portal for this address already exists: ${r.existing.name}")
                is PortalRepository.SaveResult.Invalid -> onDone(r.message)
            }
        }
    }

    fun deletePortal(p: Portal) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(p.id)
            vault.delete(p.id)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun forgetCredentials(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            vault.delete(id)
            withContext(Dispatchers.Main) { refresh(); toast("Saved login removed") }
        }
    }

    fun modify(id: String, change: (Portal) -> Portal) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.modify(id, change)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun move(id: String, delta: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.move(id, delta)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun markOpened(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.markOpened(id)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    // ---------------- settings ----------------

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        settings = change(settings)
        settingsStore.save(settings)
    }

    // ---------------- app lock ----------------

    fun onBackgrounded() { backgroundedAt = SystemClock.elapsedRealtime() }

    fun onForegrounded() {
        if (externalActivityInFlight) { externalActivityInFlight = false; return }
        if (!pinSet || locked) return
        val t = settings.autoLockSeconds
        if (t >= 0 && SystemClock.elapsedRealtime() - backgroundedAt >= t * 1000L) lockNow()
    }

    fun lockNow() { if (pinSet) locked = true }

    fun unlockWithBiometric() { locked = false }

    /** PBKDF2 is deliberately slow, so it runs off the main thread. */
    fun unlock(pinText: String, onResult: (PinManager.VerifyResult) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { pin.verify(pinText) }
            if (r is PinManager.VerifyResult.Success) locked = false
            onResult(r)
        }
    }

    fun verifyForAction(pinText: String, onResult: (PinManager.VerifyResult) -> Unit) {
        viewModelScope.launch { onResult(withContext(Dispatchers.Default) { pin.verify(pinText) }) }
    }

    fun isValidPin(p: String) = pin.isValidFormat(p)

    fun setPin(newPin: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { pin.setPin(newPin) }
            if (ok) pinSet = true
            onDone(ok)
        }
    }

    fun removePin(current: String, onResult: (PinManager.VerifyResult) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { pin.verify(current) }
            if (r is PinManager.VerifyResult.Success) {
                pin.clear(); pinSet = false; locked = false
                updateSettings { it.copy(biometricEnabled = false) }
            }
            onResult(r)
        }
    }

    fun changePin(current: String, newPin: String, onResult: (PinManager.VerifyResult) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                val v = pin.verify(current)
                if (v is PinManager.VerifyResult.Success) pin.setPin(newPin)
                v
            }
            onResult(r)
        }
    }

    /**
     * "Forgot PIN": a PIN is never revealed or bypassed. The only recovery is to wipe saved logins and
     * browser sessions (so nobody can use the reset to read credentials or ride existing sessions),
     * then remove the lock. Portal names/URLs are kept.
     */
    fun resetForgottenPin() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { vault.deleteAll() }
            WebData.clearAll(ctx)
            pin.clear(); pinSet = false; locked = false
            updateSettings { it.copy(biometricEnabled = false) }
            refresh()
            toast("Lock removed. Saved logins and browser sessions were erased.")
        }
    }

    // ---------------- data management ----------------

    fun exportJson(): String = BackupCodec.export(repo.all(), System.currentTimeMillis())

    fun writeExport(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(exportJson().toByteArray(Charsets.UTF_8)) } != null
                } catch (e: Exception) { false }
            }
            toast(if (ok) "Portal list exported (no passwords included)" else "Export failed")
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val text = readLimited(uri) ?: return@withContext "The file is too large to be a TravelHub backup"
                    when (val r = BackupCodec.parse(text)) {
                        is BackupCodec.ImportResult.Error -> r.message
                        is BackupCodec.ImportResult.Ok -> {
                            val s = repo.importAll(r.parsed.portals)
                            "Imported ${s.added}, skipped ${s.duplicates} duplicate(s) and ${s.invalid + r.parsed.rejected} invalid/insecure entr${if (s.invalid + r.parsed.rejected == 1) "y" else "ies"}. Re-enter logins; they are not in backups."
                        }
                    }
                } catch (e: Exception) { "Could not read that file" }
            }
            refresh()
            toast(msg)
        }
    }

    private fun readLimited(uri: Uri): String? {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > BackupCodec.MAX_BYTES) return null
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
        return null
    }

    fun clearBrowsingData() {
        WebData.clearAll(ctx)
        toast("Browsing data cleared")
    }

    fun deleteEverything() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.clear(); vault.deleteAll() }
            WebData.clearAll(ctx)
            refresh()
            toast("All portals, saved logins and browsing data were deleted")
        }
    }

    fun deleteAllCredentials() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { vault.deleteAll() }
            refresh()
            toast("All saved logins were deleted")
        }
    }
}
