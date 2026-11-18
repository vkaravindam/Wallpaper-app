package com.aravind.wallshift

import android.content.Context

/** Where the wallpaper gets applied. */
enum class WallTarget { HOME, LOCK, BOTH }

/** Small wrapper around SharedPreferences for all app settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wallshift", Context.MODE_PRIVATE)

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

    var lastIndex: Int
        get() = sp.getInt("lastIndex", -1)
        set(v) = sp.edit().putInt("lastIndex", v).apply()

    var lastFileName: String?
        get() = sp.getString("lastFile", null)
        set(v) = sp.edit().putString("lastFile", v).apply()

    var lastChangedAt: Long
        get() = sp.getLong("lastChangedAt", 0L)
        set(v) = sp.edit().putLong("lastChangedAt", v).apply()

    var nextChangeAt: Long
        get() = sp.getLong("nextChangeAt", 0L)
        set(v) = sp.edit().putLong("nextChangeAt", v).apply()
}
