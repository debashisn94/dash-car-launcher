package com.debashis.carlauncher

import android.content.Context
import android.location.Location

/**
 * Distance, moving time, top speed and stop count for the current journey.
 *
 * A trip is a JOURNEY, not an ignition cycle. Bhubaneswar to Bargarh is a day's drive with
 * stops for tea, lunch and fuel, and at every one of those the engine goes off and the head
 * unit powers down. Ending the trip on each stop would report the last leg instead of the
 * drive, which is useless. So a trip survives stops and only ends when the car has been
 * parked long enough that you are clearly not on the same journey any more.
 *
 * Nothing needs to be running for a trip to end. It ends by simply not being resumed, which
 * is decided by comparing a stored timestamp against the clock on the next fix.
 */
object Trip {

    private const val KEY_STARTED = "trip_started"
    private const val KEY_LAST_MOVE = "trip_last_move"
    private const val KEY_DISTANCE = "trip_distance"
    private const val KEY_MOVING = "trip_moving_ms"
    private const val KEY_MAX = "trip_max_ms"
    private const val KEY_STOPS = "trip_stops"
    private const val KEY_TOTAL = "trip_total_distance"
    const val KEY_CARD = "trip_card_enabled"

    /** Longer than any meal stop, shorter than a night. */
    private const val JOURNEY_GAP_MS = 3 * 60 * 60 * 1000L

    /** A pause longer than this counts as a stop worth reporting. */
    private const val STOP_MS = 3 * 60 * 1000L

    /**
     * Fixes worse than this are discarded. Under a flyover the receiver will happily report a
     * position 80 m away, which reads as movement and quietly adds kilometres to a parked car.
     */
    private const val MAX_ACCURACY_M = 20f

    /** Below this, a reading is standstill jitter. Same 3 km/h as the speedometer deadband. */
    private const val DEADBAND_MS = 0.833f

    /** Never bridge two fixes further apart than this, or a GPS dropout becomes a straight line. */
    private const val MAX_BRIDGE_MS = 30_000L

    private const val SAVE_EVERY_MS = 10_000L

    private var startedAt = 0L
    private var lastMoveAt = 0L
    private var distanceM = 0.0
    private var movingMs = 0L
    private var maxSpeedMs = 0f
    private var stops = 0
    private var totalM = 0.0

    private var previous: Location? = null
    private var lastSavedAt = 0L
    private var loaded = false

    // ------------------------------------------------------------------ state

    fun distanceKm() = distanceM / 1000.0
    fun totalKm() = totalM / 1000.0
    fun movingMinutes() = (movingMs / 60000L).toInt()
    fun maxSpeedMs() = maxSpeedMs
    fun stopCount() = stops
    fun hasTrip() = startedAt > 0L && distanceM > 50.0

    fun elapsedMinutes(): Int {
        if (startedAt == 0L) return 0
        val end = if (isActive()) System.currentTimeMillis() else lastMoveAt
        return ((end - startedAt) / 60000L).toInt()
    }

    /** True while the journey could still be resumed. */
    fun isActive() = lastMoveAt > 0L &&
            System.currentTimeMillis() - lastMoveAt < JOURNEY_GAP_MS

    fun cardEnabled(context: Context) = Prefs.prefs(context).getBoolean(KEY_CARD, true)

    fun setCardEnabled(context: Context, value: Boolean) {
        Prefs.prefs(context).edit().putBoolean(KEY_CARD, value).apply()
    }

    // ------------------------------------------------------------------ input

    fun load(context: Context) {
        if (loaded) return
        val p = Prefs.prefs(context)
        startedAt = p.getLong(KEY_STARTED, 0L)
        lastMoveAt = p.getLong(KEY_LAST_MOVE, 0L)
        distanceM = p.getFloat(KEY_DISTANCE, 0f).toDouble()
        movingMs = p.getLong(KEY_MOVING, 0L)
        maxSpeedMs = p.getFloat(KEY_MAX, 0f)
        stops = p.getInt(KEY_STOPS, 0)
        totalM = p.getFloat(KEY_TOTAL, 0f).toDouble()
        loaded = true
    }

    fun onLocation(context: Context, location: Location) {
        load(context)

        // Two filters, and both are needed. Without them a car sitting in a car park all
        // afternoon quietly clocks up distance, which is the bug this feature would
        // otherwise ship with.
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val speed = location.speed
        if (speed < DEADBAND_MS) {
            previous = null      // do not bridge across a stop
            return
        }

        val now = System.currentTimeMillis()
        val sinceLastMove = if (lastMoveAt == 0L) Long.MAX_VALUE else now - lastMoveAt

        when {
            sinceLastMove >= JOURNEY_GAP_MS -> startNewTrip(now)
            sinceLastMove >= STOP_MS -> stops++
        }

        val prev = previous
        if (prev != null) {
            val gap = now - lastMoveAt
            if (gap in 1..MAX_BRIDGE_MS) {
                val step = prev.distanceTo(location).toDouble()
                distanceM += step
                totalM += step
                movingMs += gap
            }
        }

        if (speed > maxSpeedMs) maxSpeedMs = speed
        lastMoveAt = now
        previous = location

        // Persist often. The unit genuinely powers down at every tea break, so the running
        // total has to survive on disk rather than in memory.
        if (now - lastSavedAt > SAVE_EVERY_MS) save(context)
    }

    fun startNewTrip(now: Long = System.currentTimeMillis()) {
        startedAt = now
        lastMoveAt = now
        distanceM = 0.0
        movingMs = 0L
        maxSpeedMs = 0f
        stops = 0
        previous = null
    }

    fun reset(context: Context) {
        startNewTrip()
        save(context)
    }

    fun save(context: Context) {
        lastSavedAt = System.currentTimeMillis()
        Prefs.prefs(context).edit()
            .putLong(KEY_STARTED, startedAt)
            .putLong(KEY_LAST_MOVE, lastMoveAt)
            .putFloat(KEY_DISTANCE, distanceM.toFloat())
            .putLong(KEY_MOVING, movingMs)
            .putFloat(KEY_MAX, maxSpeedMs)
            .putInt(KEY_STOPS, stops)
            .putFloat(KEY_TOTAL, totalM.toFloat())
            .apply()
    }

    /**
     * Average over MOVING time, not elapsed. A 331 km drive that takes 10 hours with 3 hours
     * of stops averaged against elapsed reads 33 km/h, which is the speed you drove including
     * lunch. Against moving time it reads about 47, which is the speed you actually drove.
     */
    fun averageMs(): Float {
        if (movingMs <= 0L) return 0f
        return (distanceM / (movingMs / 1000.0)).toFloat()
    }
}
