package com.aravind.wallshift

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub repo's latest release for a newer APK. Every GitHub Actions build
 * publishes a release tagged v<versionCode>, signed with the same key, so it installs
 * as an update over the existing app and keeps all wallpapers and settings.
 */
object Updater {
    data class Release(val versionCode: Int, val apkUrl: String, val tag: String)

    fun fetchLatest(repo: String): Release? {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest")
            .openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val code = tag.trimStart('v', 'V').toIntOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return Release(code, a.getString("browser_download_url"), tag)
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun download(url: String, dest: File): Boolean {
        var current = url
        repeat(5) { // follow redirects (GitHub sends release assets via a CDN)
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                when (conn.responseCode) {
                    in 300..399 -> {
                        current = conn.getHeaderField("Location") ?: return false
                    }
                    200 -> {
                        dest.parentFile?.mkdirs()
                        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                        return true
                    }
                    else -> return false
                }
            } catch (e: Exception) {
                return false
            } finally {
                conn.disconnect()
            }
        }
        return false
    }
}
