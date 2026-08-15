package com.eyerest.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * When the reminder must stay out of the way.
 *
 * Two sources, deliberately the same mechanism: standing windows the user sets
 * in the app (work hours, sleep, a meeting block), and a snooze taken from the
 * overlay itself. Both answer one question — *until when* — so the service can
 * re-arm for that moment instead of waking up to re-check.
 *
 * Times are minutes from local midnight, not clock strings: comparing 9*60 to
 * 540 needs no parsing and no timezone, and the app is the only thing that ever
 * has to render them.
 */
object Suppression {

    const val KEY_WINDOWS = "exception_windows"
    const val KEY_SNOOZE_UNTIL = "snooze_until"

    /** A window with start == end is zero-length, which reads as "off". */
    data class Window(val label: String, val start: Int, val end: Int, val on: Boolean)

    fun read(prefs: SharedPreferences): List<Window> {
        val raw = prefs.getString(KEY_WINDOWS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Window(
                    label = o.optString("label", "Quiet"),
                    start = o.optInt("start", 0).coerceIn(0, 1439),
                    end = o.optInt("end", 0).coerceIn(0, 1439),
                    on = o.optBoolean("on", true)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun write(prefs: SharedPreferences, json: String) {
        // Round-tripped through the parser so a malformed payload can never be
        // stored — a broken window would silently disable the reminder forever.
        val cleaned = JSONArray()
        for (w in parse(json)) {
            cleaned.put(
                JSONObject()
                    .put("label", w.label)
                    .put("start", w.start)
                    .put("end", w.end)
                    .put("on", w.on)
            )
        }
        prefs.edit().putString(KEY_WINDOWS, cleaned.toString()).apply()
    }

    private fun parse(json: String): List<Window> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Window(
                label = o.optString("label", "Quiet").take(40),
                start = o.optInt("start", 0).coerceIn(0, 1439),
                end = o.optInt("end", 0).coerceIn(0, 1439),
                on = o.optBoolean("on", true)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun snoozeUntil(prefs: SharedPreferences): Long = prefs.getLong(KEY_SNOOZE_UNTIL, 0L)

    fun snoozeFor(prefs: SharedPreferences, ms: Long) {
        prefs.edit().putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + ms).apply()
    }

    fun clearSnooze(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_SNOOZE_UNTIL).apply()
    }

    /**
     * 0 when the reminder is free to show, otherwise the epoch ms at which the
     * last thing suppressing it lets go. Overlapping windows extend rather than
     * compete: the latest end wins.
     */
    fun endsAt(prefs: SharedPreferences, now: Long): Long {
        var latest = 0L

        val snooze = snoozeUntil(prefs)
        if (snooze > now) latest = snooze

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val midnight = cal.timeInMillis

        for (w in read(prefs)) {
            if (!w.on || w.start == w.end) continue

            val wraps = w.start > w.end // e.g. 22:00 -> 07:00
            val inside = if (wraps) minuteOfDay >= w.start || minuteOfDay < w.end
            else minuteOfDay >= w.start && minuteOfDay < w.end
            if (!inside) continue

            // A wrapping window that we entered before midnight ends tomorrow;
            // one we are in after midnight ends today.
            val endMs = when {
                !wraps -> midnight + w.end * 60_000L
                minuteOfDay >= w.start -> midnight + 86_400_000L + w.end * 60_000L
                else -> midnight + w.end * 60_000L
            }
            if (endMs > latest) latest = endMs
        }

        return latest
    }

    /** The window covering `now`, for telling the user why it is quiet. */
    fun activeLabel(prefs: SharedPreferences, now: Long): String? {
        if (endsAt(prefs, now) <= now) return null
        if (snoozeUntil(prefs) > now) return "Snoozed"
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return read(prefs).firstOrNull { w ->
            w.on && w.start != w.end &&
                if (w.start > w.end) minuteOfDay >= w.start || minuteOfDay < w.end
                else minuteOfDay >= w.start && minuteOfDay < w.end
        }?.label
    }
}
