package com.debashis.carlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Settings. Two pane, because a single scrolling list wastes 1280x720 landscape.
 *
 * Every row is at least 76dp tall: this gets tapped in a parked car, not on a desk.
 */
class SettingsActivity : Activity() {

    private enum class Section(val title: String, val subtitle: String) {
        DOCK("Dock", "Eight slots. Tap one to choose a different app."),
        WALLPAPER("Wallpaper", "Shown behind the clock. Landscape images work best."),
        DISPLAY("Display", "Clock format and speed units."),
        ABOUT("About", "")
    }

    private companion object {
        const val REQ_PICK_APP = 10
        const val REQ_PICK_IMAGE = 11
    }

    private var section = Section.DOCK
    private var editingSlot = -1

    private lateinit var nav: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        hideSystemBars()

        nav = findViewById(R.id.nav)
        content = findViewById(R.id.content)
        titleView = findViewById(R.id.section_title)
        subView = findViewById(R.id.section_sub)

        findViewById<View>(R.id.close).setOnClickListener { finish() }

        buildNav()
        render()
    }

    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun buildNav() {
        nav.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (s in Section.values()) {
            val item = inflater.inflate(R.layout.settings_nav_item, nav, false) as TextView
            item.text = s.title
            item.isSelected = s == section
            item.setOnClickListener {
                section = s
                buildNav()
                render()
            }
            nav.addView(item)
        }
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        titleView.text = section.title
        subView.text = section.subtitle
        subView.visibility = if (section.subtitle.isEmpty()) View.GONE else View.VISIBLE
        content.removeAllViews()
        when (section) {
            Section.DOCK -> renderDock()
            Section.WALLPAPER -> renderWallpaper()
            Section.DISPLAY -> renderDisplay()
            Section.ABOUT -> renderAbout()
        }
    }

    private fun renderDock() {
        val group = newGroup()
        val slots = Prefs.dock(this)

        for ((index, slot) in slots.withIndex()) {
            val resolved = resolve(slot)
            val row = newRow(group)
            row.findViewById<TextView>(R.id.row_title).text = "Slot ${index + 1}"

            val sub = row.findViewById<TextView>(R.id.row_sub)
            val icon = row.findViewById<ImageView>(R.id.row_icon)
            val value = row.findViewById<TextView>(R.id.row_value)

            when {
                slot.component == Prefs.DRAWER -> {
                    icon.setImageResource(R.drawable.ic_all_apps)
                    sub.text = labelFor(slot, "All apps")
                    value.text = "App drawer"
                }
                slot.component.isEmpty() -> {
                    icon.setImageDrawable(null)
                    sub.text = "Empty"
                    value.text = ""
                }
                resolved == null -> {
                    icon.setImageDrawable(null)
                    // The important case: tells you WHY a tile is dimmed on the home screen
                    // instead of leaving you to guess.
                    sub.text = "Not installed on this unit"
                    sub.setTextColor(getColor(R.color.accent))
                    value.text = slot.component.substringBefore('/')
                }
                else -> {
                    icon.setImageDrawable(resolved)
                    sub.text = labelFor(slot, appLabel(slot.component))
                    value.text = slot.component.substringBefore('/')
                }
            }

            row.setOnClickListener {
                editingSlot = index
                startActivityForResult(
                    Intent(this, AppPickerActivity::class.java)
                        .putExtra(AppPickerActivity.EXTRA_SLOT, index),
                    REQ_PICK_APP
                )
            }
            group.addView(row)
        }
        content.addView(group)

        val reset = newGroup()
        val row = newRow(reset)
        row.findViewById<TextView>(R.id.row_title).apply {
            text = "Reset dock to defaults"
            setTextColor(getColor(R.color.accent))
        }
        row.findViewById<TextView>(R.id.row_sub).text = "Restores the eight shipped slots"
        row.setOnClickListener {
            Prefs.resetDock(this)
            render()
        }
        reset.addView(row)
        content.addView(spacer())
        content.addView(reset)
    }

    private fun renderWallpaper() {
        val file = File(getExternalFilesDir(null), "wallpaper.jpg")
        val group = newGroup()

        val pick = newRow(group)
        pick.findViewById<TextView>(R.id.row_title).text = "Choose an image"
        pick.findViewById<TextView>(R.id.row_sub).text =
            if (file.exists()) "Currently set, ${file.length() / 1024} KB" else "None set, showing the colour wash"
        pick.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*"),
                REQ_PICK_IMAGE
            )
        }
        group.addView(pick)

        if (file.exists()) {
            val clear = newRow(group)
            clear.findViewById<TextView>(R.id.row_title).apply {
                text = "Remove wallpaper"
                setTextColor(getColor(R.color.accent))
            }
            clear.findViewById<TextView>(R.id.row_sub).text = "Falls back to the colour wash"
            clear.setOnClickListener {
                file.delete()
                render()
            }
            group.addView(clear)
        }
        content.addView(group)
    }

    private fun renderDisplay() {
        val clockGroup = newGroup()
        val systemIs24 = DateFormat.is24HourFormat(this)
        val current = Prefs.clockFormat(this)

        addChoice(clockGroup, "Follow the unit", if (systemIs24) "Currently 24 hour" else "Currently 12 hour",
            current == Prefs.CLOCK_SYSTEM) {
            Prefs.setClockFormat(this, Prefs.CLOCK_SYSTEM); render()
        }
        addChoice(clockGroup, "12 hour", "9:59", current == 12) {
            Prefs.setClockFormat(this, 12); render()
        }
        addChoice(clockGroup, "24 hour", "21:59", current == 24) {
            Prefs.setClockFormat(this, 24); render()
        }
        content.addView(header("Clock"))
        content.addView(clockGroup)

        val unitsGroup = newGroup()
        val units = Prefs.units(this)
        addChoice(unitsGroup, "km/h", "", units == Prefs.UNITS_KMH) {
            Prefs.setUnits(this, Prefs.UNITS_KMH); render()
        }
        addChoice(unitsGroup, "mph", "", units == Prefs.UNITS_MPH) {
            Prefs.setUnits(this, Prefs.UNITS_MPH); render()
        }
        content.addView(spacer())
        content.addView(header("Speed units"))
        content.addView(unitsGroup)
    }

    private fun renderAbout() {
        val group = newGroup()
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }
        addInfo(group, "Dash", "Version $version")
        addInfo(group, "Screen", "${resources.displayMetrics.widthPixels} x " +
                "${resources.displayMetrics.heightPixels} at ${resources.displayMetrics.densityDpi} dpi")
        addInfo(group, "Android", android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")")
        addInfo(group, "Device", android.os.Build.MODEL + " / " + android.os.Build.BOARD)
        addInfo(group, "Source", "github.com/debashisn94/dash-car-launcher")
        content.addView(group)
    }

    // ----------------------------------------------------------- small parts

    private fun newGroup(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.group_bg)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun newRow(parent: ViewGroup): View =
        LayoutInflater.from(this).inflate(R.layout.settings_row, parent, false)

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(18)
        )
    }

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(getColor(R.color.muted))
        textSize = 12f
        letterSpacing = 0.09f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding(dp(6), 0, 0, dp(8))
    }

    private fun addChoice(
        group: LinearLayout, title: String, sub: String, selected: Boolean, onPick: () -> Unit
    ) {
        val row = newRow(group)
        row.findViewById<TextView>(R.id.row_title).text = title
        row.findViewById<TextView>(R.id.row_sub).apply {
            text = sub
            visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        }
        row.findViewById<TextView>(R.id.row_value).apply {
            text = if (selected) "Selected" else ""
            setTextColor(getColor(R.color.accent))
        }
        row.setOnClickListener { onPick() }
        group.addView(row)
    }

    private fun addInfo(group: LinearLayout, title: String, value: String) {
        val row = newRow(group)
        row.findViewById<TextView>(R.id.row_title).text = title
        row.findViewById<TextView>(R.id.row_sub).visibility = View.GONE
        row.findViewById<TextView>(R.id.row_value).text = value
        row.isClickable = false
        group.addView(row)
    }

    private fun labelFor(slot: Prefs.Slot, fallback: String) =
        if (slot.label.isNotBlank()) slot.label else fallback

    private fun appLabel(component: String): String {
        val pkg = component.substringBefore('/')
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    /** Null means the slot cannot be launched, which is what dims it on the home screen. */
    private fun resolve(slot: Prefs.Slot): Drawable? {
        if (slot.component.isEmpty() || slot.component == Prefs.DRAWER) return null
        return try {
            if (slot.component.contains('/')) {
                val pkg = slot.component.substringBefore('/')
                val cls = slot.component.substringAfter('/')
                val info = packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0)
                if (!info.enabled) null else info.loadIcon(packageManager)
            } else {
                if (packageManager.getLaunchIntentForPackage(slot.component) == null) null
                else packageManager.getApplicationIcon(slot.component)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- results

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQ_PICK_APP -> {
                val component = data?.getStringExtra(AppPickerActivity.EXTRA_COMPONENT) ?: return
                val label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL).orEmpty()
                if (editingSlot >= 0) {
                    Prefs.setSlot(this, editingSlot, Prefs.Slot(component, label))
                    editingSlot = -1
                    render()
                }
            }
            REQ_PICK_IMAGE -> {
                val uri = data?.data ?: return
                // Copy rather than hold the Uri: the picked document's permission does not
                // survive a reboot, and the home screen has to work on a cold start.
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        File(getExternalFilesDir(null), "wallpaper.jpg").outputStream().use { out ->
                            input.copyTo(out)
                        }
                    }
                } catch (e: Exception) {
                    // Unreadable pick. Leave whatever was there before.
                }
                render()
            }
        }
    }
}
