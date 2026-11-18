package com.aravind.wallshift

import android.annotation.SuppressLint
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.aravind.wallshift.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

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

        loadSettingsIntoUi()
        attachListeners()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- settings UI ----------

    private fun loadSettingsIntoUi() {
        b.enabledSwitch.isChecked = prefs.enabled

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
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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

        b.batteryButton.setOnClickListener { requestBatteryExemption() }
    }

    private fun refresh() {
        val files = WallpaperStore.list(this)
        adapter.submit(files, prefs.lastFileName)
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
            toast("Added $added wallpaper${if (added == 1) "" else "s"}")
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
                            // continue the sequence from this image
                            prefs.lastIndex = WallpaperStore.list(this@MainActivity)
                                .indexOfFirst { it.name == file.name }
                        }
                        toast(if (ok) "Wallpaper set" else "Couldn't set this image")
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
