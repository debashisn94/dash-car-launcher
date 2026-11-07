package com.debashis.carlauncher

import android.os.Build
import android.view.View
import android.view.Window

/**
 * Hide the status and navigation bars the same way on every internal screen,
 * with an API-aware fallback.
 *
 * API 30 (R) and up use WindowInsetsController; older releases use
 * the deprecated systemUiVisibility flags, which are still the only
 * option below API 30.
 */
object SystemBars {

    fun hide(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                // Swipe from an edge still brings them back temporarily, so the vendor
                // back and recents buttons are never permanently out of reach.
                systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}
