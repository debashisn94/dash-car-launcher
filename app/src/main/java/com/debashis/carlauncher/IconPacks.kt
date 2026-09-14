package com.debashis.carlauncher

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.LruCache
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Nova / ADW format icon pack support.
 *
 * An icon pack is an ordinary installed APK. It advertises itself with one of a handful of
 * theme intents, ships drawable resources, and maps component names to drawable names in an
 * appfilter.xml. That file turns up in two different places depending on which tool the pack
 * author used to build it: as a compiled XML resource (`res/xml/appfilter.xml`) or as a raw
 * file under `assets/appfilter.xml`. Real packs in the wild use both roughly equally, so both
 * are handled here or the feature silently fails on half of them.
 *
 * Deliberately NOT implemented: masking an app's own icon onto the pack's iconback / iconmask /
 * iconupon stack for apps the pack has no themed drawable for. That is real per-icon bitmap
 * compositing, and this runs on an RK3326 with 1.9 GB of RAM and no headroom to spend on
 * cosmetics for icons the pack author never themed. [drawableFor] returns null for those, which
 * means "draw the app's own icon", exactly like today.
 */
object IconPacks {

    /** One installed icon pack, as much as a picker list needs to show it. */
    data class Pack(val packageName: String, val label: String, val icon: Drawable?)

    /**
     * How the ecosystem advertises itself. Nova, ADW, GO Launcher EX and their descendants all
     * grew out of the same ADW theme convention, so a pack usually declares more than one of
     * these for compatibility with whichever launcher is asking.
     */
    private val THEME_INTENT_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.gau.go.launcherex.theme",
        "com.novalauncher.THEME",
        "org.adw.launcher.icons.ACTION_PICK_ICON"
    )

    /**
     * Small and fixed on purpose. A themed icon is decoded once per component and then held
     * until evicted; on a device with 1.9 GB total RAM an unbounded cache is exactly the kind
     * of leak that eventually kills the launcher process and reboots you to a blank screen.
     * 40 is comfortably more than the ten icons visible on a drawer page plus its neighbours,
     * so normal browsing never thrashes it.
     */
    private const val CACHE_SIZE = 40

    private val cache = LruCache<String, Drawable>(CACHE_SIZE)

    /** State for whichever pack was last handed to [load]. Null/empty means no pack selected. */
    @Volatile private var loadedPackage: String? = null
    @Volatile private var packResources: Resources? = null
    @Volatile private var componentMap: Map<String, String> = emptyMap()

    // Parsed from <iconback>/<iconmask>/<iconupon>/<scale> when present. Held for a future
    // masking implementation; never read today, see the class-level comment on why masking
    // is out of scope. Storing them here rather than throwing them away at least means
    // parseAppFilter has actually handled every tag real packs ship, not just <item>.
    @Volatile private var iconBackName: String? = null
    @Volatile private var iconMaskName: String? = null
    @Volatile private var iconUponName: String? = null
    @Volatile private var scaleFactor: Float = 1f

    /**
     * Every installed icon pack, deduplicated by package.
     *
     * This only queries PackageManager and loads each pack's own launcher icon, the same kind
     * of work DrawerActivity and AppPickerActivity already do on the main thread for the full
     * app list, and the number of installed icon packs is normally zero to a handful. Safe to
     * call from the main thread.
     */
    fun installed(pm: PackageManager): List<Pack> {
        val found = LinkedHashMap<String, Pack>()
        for (action in THEME_INTENT_ACTIONS) {
            val resolved = try {
                pm.queryIntentActivities(Intent(action), 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (found.containsKey(pkg)) continue
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                }
                val icon = try {
                    info.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }
                found[pkg] = Pack(pkg, label, icon)
            }
        }
        return found.values.toList()
    }

    /**
     * Loads [packageName]'s appfilter.xml and makes it the active pack for [drawableFor].
     * Pass an empty string to select "no pack" without touching PackageManager at all.
     *
     * MUST be called off the main thread. `getResourcesForApplication` and the appfilter parse
     * that follows both do file I/O against another app's APK, on storage that is slow enough
     * on this hardware to visibly stall a single UI thread mid animation. Once this returns,
     * [drawableFor] itself is cheap and safe to call from the main thread, same as decoding any
     * other drawable resource id.
     *
     * A malformed or actively hostile pack must never take the launcher down with it: every
     * failure path here degrades to "no themed icons" rather than propagating an exception.
     */
    fun load(pm: PackageManager, packageName: String) {
        clear()
        if (packageName.isEmpty()) return

        try {
            val resources = pm.getResourcesForApplication(packageName)
            val map = parseAppFilterResource(resources, packageName)
                ?: parseAppFilterAsset(resources)
                ?: emptyMap()
            packResources = resources
            componentMap = map
            loadedPackage = packageName
        } catch (e: Exception) {
            // Pack uninstalled mid-read, corrupt resources.arsc, out-of-memory decoding a
            // huge appfilter, whatever it is: fall back to no themed icons rather than crash.
            clear()
        }
    }

    /**
     * The themed drawable for [component] ("pkg/ClassName"), or null when this pack has no
     * mapping for it, which is the signal to draw the app's own icon instead.
     *
     * Safe to call from the main thread: no file I/O happens here, only an identifier lookup
     * and a bounded-cache decode, the same cost DrawerActivity already pays per visible icon.
     */
    fun drawableFor(component: String): Drawable? {
        val resources = packResources ?: return null
        val pkg = loadedPackage ?: return null
        if (pkg.isEmpty()) return null
        val drawableName = componentMap[component] ?: return null

        cache.get(component)?.let { return it }

        return try {
            val id = resources.getIdentifier(drawableName, "drawable", pkg)
            if (id == 0) return null
            val drawable = resources.getDrawable(id, null)
            cache.put(component, drawable)
            drawable
        } catch (e: Exception) {
            null
        }
    }

    /** Call when the selected pack changes, so a stale mapping never outlives its pack. */
    fun clear() {
        loadedPackage = null
        packResources = null
        componentMap = emptyMap()
        iconBackName = null
        iconMaskName = null
        iconUponName = null
        scaleFactor = 1f
        cache.evictAll()
    }

    // ------------------------------------------------------------- appfilter parsing

    /**
     * Compiled XML resource form: `res/xml/appfilter.xml`, referenced by resource name rather
     * than a fixed id since it lives inside someone else's APK. Returns null when the pack
     * simply does not ship this form, which is not an error, just a reason to try assets next.
     */
    private fun parseAppFilterResource(resources: Resources, packageName: String): Map<String, String>? {
        val id = resources.getIdentifier("appfilter", "xml", packageName)
        if (id == 0) return null
        return try {
            resources.getXml(id).use { parser -> parseItems(parser) }
        } catch (e: Exception) {
            null
        }
    }

    /** Raw asset form: `assets/appfilter.xml`, the other half of what real packs ship. */
    private fun parseAppFilterAsset(resources: Resources): Map<String, String>? {
        return try {
            resources.assets.open("appfilter.xml").use { stream ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(stream, null)
                parseItems(parser)
            }
        } catch (e: Exception) {
            // Most commonly FileNotFoundException, meaning this pack used the other form.
            null
        }
    }

    private fun parseItems(parser: XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null) {
                            componentKey(component)?.let { key -> map[key] = drawable }
                        }
                    }
                    // Recorded, not applied. See the masking note on the class and near the
                    // fields above.
                    "iconback" -> iconBackName = parser.getAttributeValue(null, "img1")
                    "iconmask" -> iconMaskName = parser.getAttributeValue(null, "img1")
                    "iconupon" -> iconUponName = parser.getAttributeValue(null, "img1")
                    "scale" -> scaleFactor =
                        parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
            }
            eventType = parser.next()
        }
        return map
    }

    /** "ComponentInfo{pkg/Class}" -> "pkg/Class", the same shape DrawerActivity keys icons by. */
    private fun componentKey(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.indexOf('}')
        if (start < 0 || end < 0 || end <= start) return null
        return raw.substring(start + 1, end)
    }
}
