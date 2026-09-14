package com.debashis.carlauncher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the user can change, in one place.
 *
 * Stored as JSON in SharedPreferences using org.json, which is part of the Android framework,
 * so this still costs zero dependencies.
 */
object Prefs {

    private const val FILE = "dash"

    const val KEY_DOCK = "dock"
    const val KEY_CLOCK_FORMAT = "clock_format"   // -1 follow system, 12, or 24
    const val KEY_UNITS = "units"                 // "kmh" or "mph"

    const val CLOCK_SYSTEM = -1
    const val UNITS_KMH = "kmh"
    const val UNITS_MPH = "mph"

    /** Marks the slot that opens the app drawer rather than an app. */
    const val DRAWER = "@drawer"

    /** Eight slots. An empty string means the slot is left blank and simply not drawn. */
    const val SLOTS = 8

    /**
     * One dock slot.
     *
     * [component] is either "" (empty), [DRAWER], a package name, or "package/class" when the
     * package exposes several launcher activities and the default one is wrong.
     * [label] overrides the app's own name. Blank means use whatever the app calls itself.
     */
    data class Slot(val component: String, val label: String = "")

    /**
     * Shipped defaults, matching the imotor firmware family this was built on.
     * On any other unit these resolve to nothing, the slots render dimmed, and the whole
     * point of the settings screen is that you can then fix them without a rebuild.
     */
    private val DEFAULTS = listOf(
        Slot("com.imotor.phoneconnect/com.imotor.phoneconnect.Carplay", "CarPlay"),
        Slot("com.netflix.mediaclient", "Netflix"),
        Slot("com.google.android.youtube", "YouTube"),
        Slot("com.imotor.music", "Music"),
        Slot("com.imotor.fmam", "Radio"),
        Slot("com.imotor.phoneconnect/com.imotor.phoneconnect.AndroidAuto", "Android Auto"),
        Slot("com.imotor.contacts/com.imotor.contacts.ui.MainActivity", "Phone"),
        Slot(DRAWER, "All apps")
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ dock

    fun dock(context: Context): List<Slot> {
        val raw = prefs(context).getString(KEY_DOCK, null) ?: return DEFAULTS
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Slot>(SLOTS)
            for (i in 0 until minOf(array.length(), SLOTS)) {
                val o = array.getJSONObject(i)
                out.add(Slot(o.optString("c", ""), o.optString("l", "")))
            }
            // Tolerate a short or corrupt list rather than crashing the home screen.
            while (out.size < SLOTS) out.add(Slot(""))
            out
        } catch (e: Exception) {
            DEFAULTS
        }
    }

    fun setSlot(context: Context, index: Int, slot: Slot) {
        if (index !in 0 until SLOTS) return
        val current = dock(context).toMutableList()
        current[index] = slot
        save(context, current)
    }

    fun resetDock(context: Context) {
        prefs(context).edit().remove(KEY_DOCK).apply()
    }

    private fun save(context: Context, slots: List<Slot>) {
        val array = JSONArray()
        for (s in slots) {
            array.put(JSONObject().put("c", s.component).put("l", s.label))
        }
        prefs(context).edit().putString(KEY_DOCK, array.toString()).apply()
    }

    // --------------------------------------------------------------- display

    /** -1 follows the unit's own setting, which is the default. */
    fun clockFormat(context: Context): Int =
        prefs(context).getInt(KEY_CLOCK_FORMAT, CLOCK_SYSTEM)

    fun setClockFormat(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_CLOCK_FORMAT, value).apply()
    }

    fun units(context: Context): String =
        prefs(context).getString(KEY_UNITS, UNITS_KMH) ?: UNITS_KMH

    fun setUnits(context: Context, value: String) {
        prefs(context).edit().putString(KEY_UNITS, value).apply()
    }
}
