package com.debashis.carlauncher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import kotlin.math.abs

/**
 * A HorizontalScrollView that snaps to whole pages.
 *
 * Framework only, no ViewPager2, because pulling in AndroidX for one screen would cost
 * more than this whole app.
 *
 * The page decision is made from the gesture that just happened, NOT from where the
 * content happened to come to rest:
 *
 *   - dragged more than [DISTANCE_RATIO] of a page in one direction  -> change page
 *   - or flicked faster than [MIN_FLING_VELOCITY]                    -> change page
 *   - otherwise                                                      -> spring back
 *
 * An earlier version snapped to whichever page was nearest, which meant a short flick
 * always sprang back: a small gesture never moves scrollX past the halfway mark, so the
 * nearest page is still the one you started on. Only an edge to edge drag crossed 50 percent.
 * That is the bug this class exists to not have.
 */
class PagerScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : HorizontalScrollView(context, attrs, defStyle) {

    var pageCount: Int = 1
    var onPageChanged: ((Int) -> Unit)? = null

    private companion object {
        /**
         * Eight percent of a page is enough intent to commit to the next one.
         * Measured on device: a quick 120ms flick only moved scrollX by 137px, so a 12%
         * threshold (153px at 1280 wide) still fell short.
         */
        const val DISTANCE_RATIO = 0.08f

        /**
         * Pixels per second. Deliberately close to the platform's own minimum fling so a
         * normal flick registers. The previous value of 900 was well above it, which is
         * why quick gestures did nothing at all.
         */
        const val MIN_FLING_VELOCITY = 320
    }

    /** scrollX when the finger went down, so we measure the gesture rather than the rest position. */
    private var downScrollX = 0
    private var flingHandled = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downScrollX = scrollX
            flingHandled = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downScrollX = scrollX
            flingHandled = false
        }

        // super may call fling() synchronously while handling ACTION_UP.
        val handled = super.onTouchEvent(ev)

        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!flingHandled) goTo(pageFromDistance())
        }
        return handled
    }

    /** Swallow the normal fling so the view can never come to rest between pages. */
    override fun fling(velocityX: Int) {
        flingHandled = true

        val pageWidth = width
        if (pageWidth == 0) {
            super.fling(velocityX)
            return
        }

        val startPage = downScrollX / pageWidth
        val target = when {
            // SIGN TRAP: AOSP HorizontalScrollView calls fling(-initialVelocity), i.e. it
            // negates before handing the value over. A right to left swipe (finger moving
            // left, advance a page) therefore arrives here POSITIVE. Verified on device:
            // swiping 900 -> 740 logged v=+1333.
            velocityX >= MIN_FLING_VELOCITY -> startPage + 1
            velocityX <= -MIN_FLING_VELOCITY -> startPage - 1
            // Slow release: fall back to how far they actually dragged.
            else -> pageFromDistance()
        }
        goTo(target)
    }

    private fun pageFromDistance(): Int {
        val pageWidth = width
        if (pageWidth == 0) return 0
        val startPage = downScrollX / pageWidth
        val dragged = scrollX - downScrollX
        val threshold = pageWidth * DISTANCE_RATIO
        return when {
            dragged > threshold -> startPage + 1
            dragged < -threshold -> startPage - 1
            else -> startPage
        }
    }

    private fun goTo(page: Int) {
        val pageWidth = width
        if (pageWidth == 0) return
        val clamped = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        // Only animate when there is somewhere to go, otherwise the spring back is a no-op.
        if (abs(scrollX - clamped * pageWidth) > 0) {
            smoothScrollTo(clamped * pageWidth, 0)
        }
        onPageChanged?.invoke(clamped)
    }
}
