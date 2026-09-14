package com.debashis.carlauncher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Detects which firmware family a head unit is running, so the dock can be pre-filled with
 * something that actually resolves instead of eight dimmed tiles pointing at imotor packages.
 *
 * Pure logic, no Android UI. Everything here must be safe to call from a first-run screen
 * before the user has granted anything or the unit has settled, so every lookup is wrapped
 * and a failure just means "this candidate did not match" rather than a crash.
 */
object VendorProfiles {

    enum class Role { PHONE_MIRRORING, MUSIC, RADIO, PHONE, AUX, VIDEO, SETTINGS, BLUETOOTH }

    /**
     * One firmware family. [candidates] is checked in order per role, first match wins,
     * because a family sometimes ships more than one plausible component for the same job
     * (imotor's phoneconnect package alone exposes four launcher activities).
     */
    data class Profile(val family: String, val candidates: Map<Role, List<String>>)

    data class Result(
        val family: String,
        val filled: Int,
        val attempted: Int,
        val resolved: Map<Role, String>
    )

    // ---------------------------------------------------------------- profiles

    /**
     * imotor: VERIFIED on a real RK3326 unit (see README, "Seven bugs"). Component-level
     * targeting is required for phoneconnect because the package default resolves to the
     * wrong activity and the generic Android robot icon.
     */
    private val IMOTOR = Profile(
        family = "imotor",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf(
                "com.imotor.phoneconnect/com.imotor.phoneconnect.Carplay",
                "com.imotor.phoneconnect/com.imotor.phoneconnect.AndroidAuto"
            ),
            Role.MUSIC to listOf("com.imotor.music"),
            Role.RADIO to listOf("com.imotor.fmam"),
            Role.PHONE to listOf("com.imotor.contacts/com.imotor.contacts.ui.MainActivity"),
            Role.AUX to listOf("com.imotor.aux", "com.imotor.avin"),
            Role.VIDEO to listOf("com.imotor.video"),
            Role.SETTINGS to listOf("com.android.settings"),
            Role.BLUETOOTH to listOf("com.imotor.btmusic")
        )
    )

    /**
     * FYT, TS-series, and MTK/AC8227L: BEST-EFFORT, not verified on real hardware. These
     * package names are the ones commonly reported by owners of those units in forum threads
     * and teardown notes, not something checked with adb here. Listing them is still useful
     * because a wrong guess just fails to match and falls through to the generic pass below;
     * it never produces a false positive that points at the wrong app, since [detect] only
     * counts a candidate when PackageManager confirms it is actually installed.
     */
    private val FYT = Profile(
        family = "FYT",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf("com.mixtorrent.carplayer", "com.hct.carplay.cp"),
            Role.MUSIC to listOf("com.android.music", "cn.kuwo.player"),
            Role.RADIO to listOf("com.android.fmradio"),
            Role.PHONE to listOf("com.android.contacts"),
            Role.AUX to listOf("com.android.aux"),
            Role.VIDEO to listOf("com.android.gallery3d"),
            Role.SETTINGS to listOf("com.android.settings"),
            Role.BLUETOOTH to listOf("com.android.bluetooth")
        )
    )

    private val TS_SERIES = Profile(
        family = "TS-series",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf("com.eny.carplay", "com.carlink.carplay"),
            Role.MUSIC to listOf("com.ts.music", "com.android.music"),
            Role.RADIO to listOf("com.ts.radio", "com.android.fmradio"),
            Role.PHONE to listOf("com.android.contacts"),
            Role.AUX to listOf("com.ts.aux"),
            Role.VIDEO to listOf("com.android.gallery3d"),
            Role.SETTINGS to listOf("com.android.settings"),
            Role.BLUETOOTH to listOf("com.android.bluetooth")
        )
    )

    private val MTK_AC8227L = Profile(
        family = "MTK/AC8227L",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf("com.mtcmobi.hicar", "com.mtk.carplay"),
            Role.MUSIC to listOf("com.android.music"),
            Role.RADIO to listOf("com.android.fmradio"),
            Role.PHONE to listOf("com.android.contacts"),
            Role.AUX to listOf("com.mtk.aux"),
            Role.VIDEO to listOf("com.android.gallery3d"),
            Role.SETTINGS to listOf("com.android.settings"),
            Role.BLUETOOTH to listOf("com.android.bluetooth")
        )
    )

    private val NAMED_PROFILES = listOf(IMOTOR, FYT, TS_SERIES, MTK_AC8227L)

    /**
     * Package-name substrings for the generic fallback pass. Deliberately loose: this is
     * "does the package name suggest this role", not a lookup table, so it is expected to
     * both miss real matches and occasionally grab the wrong app. It only runs after every
     * named profile has been tried and only fills roles still empty, so it can only add
     * something rather than override a real match.
     */
    private val GENERIC_HINTS: Map<Role, List<String>> = mapOf(
        Role.PHONE_MIRRORING to listOf("carplay", "androidauto", "carlink", "hicar", "airplay"),
        Role.MUSIC to listOf("music", "player", "mediaplayer"),
        Role.RADIO to listOf("radio", "fmam", "fmradio"),
        Role.PHONE to listOf("contacts", "dialer", "phone"),
        Role.AUX to listOf("aux", "avin"),
        Role.VIDEO to listOf("video", "gallery", "filemanager"),
        Role.SETTINGS to listOf("settings"),
        Role.BLUETOOTH to listOf("btmusic", "bluetooth")
    )

    // ------------------------------------------------------------------ detect

    /**
     * Never throws. Every PackageManager call is wrapped so a single odd component on an
     * unfamiliar unit cannot take down a screen that exists specifically to help unfamiliar
     * units.
     */
    fun detect(pm: PackageManager): Result {
        var best: Result? = null

        for (profile in NAMED_PROFILES) {
            val resolved = HashMap<Role, String>()
            for ((role, candidates) in profile.candidates) {
                val match = candidates.firstOrNull { isUsable(pm, it) }
                if (match != null) resolved[role] = match
            }
            val attempted = profile.candidates.size
            if (best == null || resolved.size > best.filled) {
                best = Result(profile.family, resolved.size, attempted, resolved)
            }
        }

        // A named profile that matched nothing is worse than useless: it labels the unit
        // with a family name that is wrong. Fall through to the generic pass instead.
        val current = best
        if (current == null || current.filled == 0) {
            return genericFallback(pm)
        }

        // Even a partial named match is topped up by the generic pass for whatever roles
        // it left empty, so e.g. a real imotor unit still gets Video filled if that role
        // was not in the profile's candidate list.
        val topUp = genericFallback(pm, alreadyResolved = current.resolved)
        return Result(current.family, topUp.resolved.size, GENERIC_HINTS.size, topUp.resolved)
    }

    private fun genericFallback(
        pm: PackageManager,
        alreadyResolved: Map<Role, String> = emptyMap()
    ): Result {
        val resolved = HashMap<Role, String>(alreadyResolved)
        val launchable = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }

        for ((role, hints) in GENERIC_HINTS) {
            if (resolved.containsKey(role)) continue
            for (info in launchable) {
                val pkg = try {
                    info.activityInfo.packageName
                } catch (e: Exception) {
                    continue
                }
                if (hints.any { pkg.contains(it, ignoreCase = true) }) {
                    resolved[role] = pkg
                    break
                }
            }
        }
        return Result("Generic Android", resolved.size, GENERIC_HINTS.size, resolved)
    }

    /** A candidate counts only if it is installed AND launchable, never just "present". */
    private fun isUsable(pm: PackageManager, component: String): Boolean {
        return try {
            if (component.contains('/')) {
                val pkg = component.substringBefore('/')
                val cls = component.substringAfter('/')
                val info = pm.getActivityInfo(ComponentName(pkg, cls), 0)
                info.enabled
            } else {
                pm.getLaunchIntentForPackage(component) != null
            }
        } catch (e: Exception) {
            false
        }
    }

    // -------------------------------------------------------------- dock slots

    /**
     * Fixed slot order chosen to match what a driver reaches for most: mirroring and music up
     * front, drawer always last. Roles with no match leave the slot blank rather than guessing,
     * since a blank tile is honest and a wrong one is a tap that does nothing useful while
     * driving.
     */
    private val SLOT_ORDER = listOf(
        Role.PHONE_MIRRORING, Role.MUSIC, Role.RADIO, Role.PHONE,
        Role.BLUETOOTH, Role.AUX, Role.VIDEO, Role.SETTINGS
    )

    /** Always returns exactly [Prefs.SLOTS] entries, with the drawer marker in the last one. */
    fun toDockComponents(result: Result): List<String> {
        val out = ArrayList<String>(Prefs.SLOTS)
        for (i in 0 until Prefs.SLOTS - 1) {
            val role = SLOT_ORDER.getOrNull(i)
            out.add(if (role != null) result.resolved[role].orEmpty() else "")
        }
        out.add(Prefs.DRAWER)
        return out
    }
}
