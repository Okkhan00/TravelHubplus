package com.tabeer.travelhub.data

import android.content.SharedPreferences

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ViewMode { LIST, GRID }
enum class SortMode { MANUAL, ALPHABETICAL, RECENT }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val view: ViewMode = ViewMode.LIST,
    val sort: SortMode = SortMode.MANUAL,
    val defaultOpenMode: OpenMode = OpenMode.IN_APP,
    /** Seconds in background before the app locks; 0 = immediately, -1 = never. */
    val autoLockSeconds: Int = 60,
    val blockScreenshots: Boolean = true,
    val biometricEnabled: Boolean = false,
    val showDisabled: Boolean = false,
)

/** Non-sensitive UI preferences. No credentials are ever stored here. */
class SettingsStore(private val prefs: SharedPreferences) {
    private inline fun <reified T : Enum<T>> en(key: String, def: T): T =
        runCatching { enumValueOf<T>(prefs.getString(key, null) ?: "") }.getOrDefault(def)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = en("theme", d.theme),
            view = en("view", d.view),
            sort = en("sort", d.sort),
            defaultOpenMode = en("defaultOpenMode", d.defaultOpenMode),
            autoLockSeconds = prefs.getInt("autoLockSeconds", d.autoLockSeconds),
            blockScreenshots = prefs.getBoolean("blockScreenshots", d.blockScreenshots),
            biometricEnabled = prefs.getBoolean("biometricEnabled", d.biometricEnabled),
            showDisabled = prefs.getBoolean("showDisabled", d.showDisabled),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("theme", s.theme.name)
            .putString("view", s.view.name)
            .putString("sort", s.sort.name)
            .putString("defaultOpenMode", s.defaultOpenMode.name)
            .putInt("autoLockSeconds", s.autoLockSeconds)
            .putBoolean("blockScreenshots", s.blockScreenshots)
            .putBoolean("biometricEnabled", s.biometricEnabled)
            .putBoolean("showDisabled", s.showDisabled)
            .apply()
    }
}
