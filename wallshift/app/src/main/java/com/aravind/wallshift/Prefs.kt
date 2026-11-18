package com.aravind.wallshift

import android.content.Context
import android.content.SharedPreferences

/** Where the wallpaper gets applied. */
enum class WallTarget { HOME, LOCK, BOTH }

/** Small wrapper around SharedPreferences for all app settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wallshift", Context.MODE_PRIVATE)

    companion object {
        const val KEY_HOME_FILE = "homeFile"
        const val KEY_PARALLAX = "parallax"
    }

    fun register(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregister(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var intervalMinutes: Int
        get() = sp.getInt("interval", 60)
        set(v) = sp.edit().putInt("interval", v).apply()

    var target: WallTarget
        get() = WallTarget.entries[sp.getInt("target", WallTarget.BOTH.ordinal)]
        set(v) = sp.edit().putInt("target", v.ordinal).apply()

    var shuffle: Boolean
        get() = sp.getBoolean("shuffle", false)
        set(v) = sp.edit().putBoolean("shuffle", v).apply()

    /** Pan wide wallpapers as you swipe between home screen pages. */
    var parallax: Boolean
        get() = sp.getBoolean(KEY_PARALLAX, true)
        set(v) = sp.edit().putBoolean(KEY_PARALLAX, v).apply()

    var lastIndex: Int
        get() = sp.getInt("lastIndex", -1)
        set(v) = sp.edit().putInt("lastIndex", v).apply()

    /** File currently on the home screen (the live engine watches this key). */
    var homeFileName: String?
        get() = sp.getString(KEY_HOME_FILE, null)
        set(v) = sp.edit().putString(KEY_HOME_FILE, v).apply()

    var lockFileName: String?
        get() = sp.getString("lockFile", null)
        set(v) = sp.edit().putString("lockFile", v).apply()

    /** Last file picked by the rotation (used for shuffle's no-repeat rule). */
    var lastFileName: String?
        get() = sp.getString("lastFile", null)
        set(v) = sp.edit().putString("lastFile", v).apply()

    var lastChangedAt: Long
        get() = sp.getLong("lastChangedAt", 0L)
        set(v) = sp.edit().putLong("lastChangedAt", v).apply()

    var nextChangeAt: Long
        get() = sp.getLong("nextChangeAt", 0L)
        set(v) = sp.edit().putLong("nextChangeAt", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()
}
