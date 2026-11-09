package com.peak.officesentry

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.os.Bundle
import android.os.SystemClock
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the phone's calendar (whatever Google account is signed in) and has
 * Garrick announce upcoming events at the desk [Prefs.calLeadMin] minutes ahead.
 * Call [check] about once a minute.
 */
class CalendarWatcher(private val ctx: Context, private val prefs: Prefs, private val desk: DeskLink) {

    fun hasPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private var lastSync = 0L

    /**
     * Ask Android to pull fresh calendar data now (e.g. an event James just added on
     * his PC). Async: the provider updates a few seconds later. Throttled.
     */
    fun requestSync(minGapMs: Long = 60_000) {
        if (!hasPermission()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSync < minGapMs && lastSync != 0L) return
        lastSync = now
        try {
            val accounts = mutableSetOf<Pair<String, String>>()
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE),
                "${CalendarContract.Calendars.VISIBLE} = 1", null, null
            )?.use { c -> while (c.moveToNext()) accounts += (c.getString(0) ?: continue) to (c.getString(1) ?: continue) }
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            for ((name, type) in accounts) {
                if (type == CalendarContract.ACCOUNT_TYPE_LOCAL) continue
                runCatching { ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, extras) }
            }
        } catch (e: Exception) {
            Log.w("CalendarWatcher", "sync request failed", e)
        }
    }

    /**
     * Plain-text schedule for Garrick: what's happening now and the rest of today
     * (into tomorrow morning). Empty string if no permission / nothing scheduled.
     */
    fun scheduleSummary(): String {
        if (!hasPermission()) return ""
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 12 * 3_600_000L)
            ContentUris.appendId(it, now + 18 * 3_600_000L)
        }.build()
        val fmt = SimpleDateFormat("h:mm a", Locale.US)
        val day = SimpleDateFormat("EEE", Locale.US)
        val today = day.format(Date(now))
        val now_ = mutableListOf<String>(); val later = mutableListOf<String>(); val allDay = mutableListOf<String>()
        try {
            ctx.contentResolver.query(uri, arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            ), null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0)?.trim().orEmpty().ifEmpty { "Busy" }
                    val b = c.getLong(1); val e = c.getLong(2); val ad = c.getInt(3) == 1
                    if (e <= now) continue
                    if (ad) { if (b <= now + 86_400_000L) allDay += title; continue }
                    val dayTag = day.format(Date(b)).let { if (it == today) "" else "$it " }
                    val span = "$dayTag${fmt.format(Date(b))}-${fmt.format(Date(e))}: $title"
                    if (b <= now) now_ += span else if (later.size < 6) later += span
                }
            }
        } catch (e: Exception) {
            Log.w("CalendarWatcher", "summary failed", e); return ""
        }
        if (now_.isEmpty() && later.isEmpty() && allDay.isEmpty()) return "Calendar: nothing else scheduled today."
        return buildString {
            append("Calendar (current time ${fmt.format(Date(now))}):\n")
            if (now_.isNotEmpty()) append("- HAPPENING NOW: ${now_.joinToString("; ")}\n") else append("- Nothing on the calendar right now.\n")
            if (later.isNotEmpty()) append("- Later: ${later.joinToString("; ")}\n")
            if (allDay.isNotEmpty()) append("- All-day: ${allDay.joinToString("; ")}\n")
        }.trim()
    }

    fun check() {
        if (!prefs.calEnabled || !hasPermission()) return
        val now = System.currentTimeMillis()
        val lead = prefs.calLeadMin * 60_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + lead + 60_000)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val announced = prefs.announced.toMutableSet()
        try {
            ctx.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1)?.trim().orEmpty().ifEmpty { "an appointment" }
                    val begin = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    val loc = c.getString(4)?.trim().orEmpty()
                    if (allDay || begin <= now || begin - now > lead) continue
                    val key = "$id@$begin"
                    if (key in announced) continue
                    announced += key
                    val mins = ((begin - now + 30_000) / 60_000).toInt()
                    val at = SimpleDateFormat("h:mm a", Locale.US).format(Date(begin))
                    val detail = "\"$title\" at $at" + (if (loc.isNotBlank()) " at $loc" else "") + ", starting in $mins minutes."
                    desk.announce("calendar", detail,
                        "Lord ${prefs.ownerName}, a reminder: $title at $at, in $mins minutes.")
                }
            }
        } catch (e: Exception) {
            Log.w("CalendarWatcher", "query failed", e)
        }
        prefs.announced = announced.toList().takeLast(200).toSet()
    }
}
