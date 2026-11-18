package com.aravind.wallshift

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.aravind.wallshift.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: WallpaperAdapter

    private val intervals = listOf(
        15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour", 120 to "2 hours",
        180 to "3 hours", 360 to "6 hours", 720 to "12 hours", 1440 to "24 hours",
    )

    private val picker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris -> if (uris.isNotEmpty()) importUris(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        adapter = WallpaperAdapter(lifecycleScope) { showItemMenu(it) }
        b.grid.layoutManager = GridLayoutManager(this, 3)
        b.grid.adapter = adapter
        b.versionText.text = "Version ${BuildConfig.VERSION_NAME}"

        loadSettingsIntoUi()
        attachListeners()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (System.currentTimeMillis() - prefs.lastUpdateCheck > TimeUnit.HOURS.toMillis(6)) {
            checkForUpdates(manual = false)
        }
    }

    // ---------- settings UI ----------

    private fun loadSettingsIntoUi() {
        b.enabledSwitch.isChecked = prefs.enabled
        b.parallaxSwitch.isChecked = prefs.parallax

        b.intervalSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, intervals.map { it.second }
        )
        val idx = intervals.indexOfFirst { it.first == prefs.intervalMinutes }.coerceAtLeast(0)
        b.intervalSpinner.setSelection(idx, false)

        b.targetGroup.check(
            when (prefs.target) {
                WallTarget.HOME -> R.id.targetHome
                WallTarget.LOCK -> R.id.targetLock
                WallTarget.BOTH -> R.id.targetBoth
            }
        )
        b.orderGroup.check(if (prefs.shuffle) R.id.orderShuffle else R.id.orderSequence)
    }

    private fun attachListeners() {
        b.enabledSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            if (checked && WallpaperStore.list(this).isEmpty()) {
                toast("Add some wallpapers first. Rotation starts once you do.")
            }
            Rotator.reschedule(this)
            refresh()
        }

        b.parallaxSwitch.setOnCheckedChangeListener { _, checked -> prefs.parallax = checked }

        b.intervalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val minutes = intervals[pos].first
                if (minutes != prefs.intervalMinutes) {
                    prefs.intervalMinutes = minutes
                    Rotator.reschedule(this@MainActivity)
                    refresh()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.targetGroup.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.target = when (id) {
                R.id.targetHome -> WallTarget.HOME
                R.id.targetLock -> WallTarget.LOCK
                else -> WallTarget.BOTH
            }
        }

        b.orderGroup.addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked) prefs.shuffle = (id == R.id.orderShuffle)
        }

        b.addButton.setOnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }

        b.changeNowButton.setOnClickListener {
            b.changeNowButton.isEnabled = false
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { Rotator.changeNow(applicationContext) }
                toast(if (ok) "Wallpaper changed" else "No wallpapers to apply")
                b.changeNowButton.isEnabled = true
                refresh()
            }
        }

        b.liveButton.setOnClickListener { turnOnLiveWallpaper() }
        b.batteryButton.setOnClickListener { requestBatteryExemption() }
        b.updateButton.setOnClickListener { checkForUpdates(manual = true) }
    }

    private fun refresh() {
        val files = WallpaperStore.list(this)
        adapter.submit(files, prefs.homeFileName, prefs.lockFileName)
        b.emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        b.countText.text = "${files.size} wallpaper${if (files.size == 1) "" else "s"}"

        val fmt = DateFormat.getTimeFormat(this)
        val parts = mutableListOf<String>()
        if (prefs.lastChangedAt > 0) parts += "Last changed ${fmt.format(Date(prefs.lastChangedAt))}"
        if (prefs.enabled && prefs.nextChangeAt > 0 && files.isNotEmpty()) {
            parts += "next around ${fmt.format(Date(prefs.nextChangeAt))}"
        } else if (!prefs.enabled) {
            parts += "auto-change is off"
        }
        b.statusText.text = parts.joinToString(" · ")

        val live = Rotator.isLiveActive(this)
        b.liveStatusText.text = if (live) {
            "On. GIFs and videos play on the home screen, and wide wallpapers scroll as you swipe between pages."
        } else {
            "Off. Turn it on to play GIFs and videos and to scroll wide wallpapers with your home pages. " +
                "Without it, GIFs and videos are shown as still images."
        }
        b.liveButton.visibility = if (live) View.GONE else View.VISIBLE

        val pm = getSystemService(PowerManager::class.java)
        b.batteryButton.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    // ---------- wallpapers ----------

    private fun importUris(uris: List<Uri>) {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.withIndex().count { (i, u) -> WallpaperStore.import(applicationContext, u, i) }
            }
            toast("Added $added item${if (added == 1) "" else "s"}")
            refresh()
        }
    }

    private fun showItemMenu(file: File) {
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf("Set as wallpaper now", "Remove from rotation")) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            Rotator.applyFile(applicationContext, file)
                        }
                        if (ok) {
                            // continue the sequence from this item
                            prefs.lastIndex = WallpaperStore.list(this@MainActivity)
                                .indexOfFirst { it.name == file.name }
                        }
                        toast(if (ok) "Wallpaper set" else "Couldn't set this file")
                        refresh()
                    }
                    1 -> {
                        WallpaperStore.delete(file)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun turnOnLiveWallpaper() {
        try {
            startActivity(Rotator.liveWallpaperIntent(this))
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                toast("Pick WallShift from the list")
            } catch (e2: Exception) {
                toast("Couldn't open the live wallpaper screen")
            }
        }
    }

    // ---------- updates ----------

    private fun checkForUpdates(manual: Boolean) {
        b.updateButton.isEnabled = false
        lifecycleScope.launch {
            val rel = withContext(Dispatchers.IO) { Updater.fetchLatest(BuildConfig.UPDATE_REPO) }
            prefs.lastUpdateCheck = System.currentTimeMillis()
            b.updateButton.isEnabled = true
            when {
                rel == null -> if (manual) toast("Couldn't check for updates")
                rel.versionCode <= BuildConfig.VERSION_CODE ->
                    if (manual) toast("You're on the latest version")
                else -> MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Update available")
                    .setMessage(
                        "Build ${rel.versionCode} is ready (you have ${BuildConfig.VERSION_CODE}).\n\n" +
                            "It installs over this version. Your wallpapers and settings are kept."
                    )
                    .setPositiveButton("Update") { _, _ -> downloadAndInstall(rel) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun downloadAndInstall(rel: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Allow WallShift to install updates, then come back and tap Update again")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        toast("Downloading update…")
        lifecycleScope.launch {
            val dir = File(cacheDir, "updates")
            val apk = File(dir, "WallShift-${rel.versionCode}.apk")
            val ok = withContext(Dispatchers.IO) {
                dir.deleteRecursively()
                Updater.download(rel.apkUrl, apk)
            }
            if (!ok) {
                toast("Download failed. Try again later.")
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", apk)
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    // ---------- battery ----------

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
