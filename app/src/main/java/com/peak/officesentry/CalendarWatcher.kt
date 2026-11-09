package com.peak.officesentry

import android.Manifest
import android.content.ContentUris
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
