package com.debashis.carlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * All apps.
 *
 * Paged left to right, ten big icons per page, flick to change page.
 * Disabled packages are not listed at all.
 */
class DrawerActivity : Activity() {

    private data class Entry(val label: String, val pkg: String, val icon: Drawable)

    private companion object {
        const val COLUMNS = 5
        const val ROWS = 2
        const val PER_PAGE = COLUMNS * ROWS
        const val SIDE_PADDING_DP = 40
    }

    private val entries = mutableListOf<Entry>()
    private val dots = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drawer)
        hideSystemBars()

        findViewById<View>(R.id.back).setOnClickListener { finish() }

        loadApps()
        findViewById<TextView>(R.id.count).text = "${entries.size} apps"

        buildPages()
    }

    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun loadApps() {
        entries.clear()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        // Default flags exclude disabled components, which is what we want: a debloated
        // package should simply not appear here.
        val resolved: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)

        val seen = HashSet<String>()
        for (info in resolved) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue      // do not list ourselves
            if (!seen.add(pkg)) continue          // one row per package
            entries.add(
                Entry(
                    label = info.loadLabel(packageManager).toString(),
                    pkg = pkg,
                    icon = info.loadIcon(packageManager)
                )
            )
        }
        entries.sortBy { it.label.lowercase() }
    }

    private fun buildPages() {
        val pager = findViewById<PagerScrollView>(R.id.pager)
        val pages = findViewById<LinearLayout>(R.id.pages)
        val dotBar = findViewById<LinearLayout>(R.id.dots)
        val inflater = LayoutInflater.from(this)

        pages.removeAllViews()
        dotBar.removeAllViews()
        dots.clear()

        val pageWidth = resources.displayMetrics.widthPixels
        val sidePadding = dp(SIDE_PADDING_DP)
        val cellWidth = (pageWidth - sidePadding * 2) / COLUMNS

        val chunks = entries.chunked(PER_PAGE)
        for (chunk in chunks) {
            val grid = GridLayout(this).apply {
                columnCount = COLUMNS
                rowCount = ROWS
                setPadding(sidePadding, dp(12), sidePadding, dp(12))
                layoutParams = LinearLayout.LayoutParams(pageWidth, LinearLayout.LayoutParams.MATCH_PARENT)
            }

            for ((index, entry) in chunk.withIndex()) {
                val view = inflater.inflate(R.layout.app_item_big, grid, false)
                view.findViewById<ImageView>(R.id.icon).setImageDrawable(entry.icon)
                view.findViewById<TextView>(R.id.label).text = entry.label
                view.setOnClickListener {
                    packageManager.getLaunchIntentForPackage(entry.pkg)?.let { startActivity(it) }
                }

                val params = GridLayout.LayoutParams(
                    GridLayout.spec(index / COLUMNS, 1f),
                    GridLayout.spec(index % COLUMNS, 1f)
                ).apply {
                    width = cellWidth
                    height = 0                 // 0 with a row weight means share the height
                }
                grid.addView(view, params)
            }
            pages.addView(grid)
        }

        pager.pageCount = chunks.size.coerceAtLeast(1)

        // Page dots. Only worth drawing when there is more than one page.
        if (chunks.size > 1) {
            for (i in chunks.indices) {
                val dot = View(this)
                val params = LinearLayout.LayoutParams(dp(10), dp(10))
                params.marginStart = dp(6)
                params.marginEnd = dp(6)
                dot.layoutParams = params
                dot.setBackgroundResource(if (i == 0) R.drawable.dot_active else R.drawable.dot)
                dotBar.addView(dot)
                dots.add(dot)
            }
            dotBar.gravity = Gravity.CENTER
            pager.onPageChanged = { page -> setActiveDot(page) }
        }
    }

    private fun setActiveDot(page: Int) {
        for ((i, dot) in dots.withIndex()) {
            dot.setBackgroundResource(if (i == page) R.drawable.dot_active else R.drawable.dot)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        // Apps may have been installed, enabled or disabled while we were away.
        val before = entries.map { it.pkg }
        loadApps()
        if (entries.map { it.pkg } != before) {
            findViewById<TextView>(R.id.count).text = "${entries.size} apps"
            buildPages()
        }
    }
}
