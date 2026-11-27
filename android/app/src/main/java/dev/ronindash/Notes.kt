package dev.ronindash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Issues the rider writes down after a ride ("the arrow stayed after the turn", "assistant opened by itself").
 * Each note keeps when it was written, roughly when it happened, and which ride log it belongs to,
 * so the note can be matched to the exact lines in logs/ride_*.csv.
 *
 * Files (readable over USB, no root):
 *   adb pull /sdcard/Android/data/dev.ronindash/files/notes/
 *   notes.jsonl : one JSON object per line (the source of truth)
 *   ISSUES.md   : the same notes, human readable, rewritten on every change
 */
data class Note(
    val id: Long,
    val written: Long,
    /** Rough time it happened (written minus "how long ago"), null = rider wasn't sure. */
    val happened: Long?,
    val agoMin: Int?,
    val text: String,
    /** Ride log file name that covers the moment (or the latest one), "" if none. */
    val ride: String,
    val version: String,
    val bikeOn: Boolean,
)

object Notes {
    val list = MutableStateFlow<List<Note>>(emptyList())
    private var dir: File? = null
    private val day = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US)

    fun load(ctx: Context) {
        val d = File(ctx.getExternalFilesDir(null), "notes").apply { mkdirs() }
        dir = d
        val f = File(d, "notes.jsonl")
        list.value = if (!f.exists()) emptyList() else f.readLines().mapNotNull { l ->
            try {
                val o = JSONObject(l)
                Note(o.getLong("id"), o.getLong("written"), if (o.isNull("happened")) null else o.getLong("happened"),
                    if (o.isNull("agoMin")) null else o.getInt("agoMin"), o.getString("text"), o.optString("ride"),
                    o.optString("version"), o.optBoolean("bikeOn"))
            } catch (_: Exception) { null }
        }
    }

    fun add(ctx: Context, text: String, agoMin: Int?) {
        if (dir == null) load(ctx)
        val now = System.currentTimeMillis()
        val happened = agoMin?.let { now - it * 60_000L }
        val n = Note(now, now, happened, agoMin, text.trim(), rideFor(ctx, happened ?: now), BuildConfig.VERSION_NAME, Bike.service != null)
        list.value = list.value + n
        save()
        // Also into the live ride log when the bike is on, so it sits between the frames around it.
        if (Bike.service != null) Bike.log("[note] ${n.text}" + (agoMin?.let { " (about $it min ago)" } ?: ""))
    }

    fun delete(id: Long) { list.value = list.value.filterNot { it.id == id }; save() }

    /** The ride log whose time span covers t; else the newest log that started before t; else the newest. */
    private fun rideFor(ctx: Context, t: Long): String {
        val logs = File(ctx.getExternalFilesDir(null), "logs").listFiles { f -> f.name.startsWith("ride_") }?.toList().orEmpty()
        if (logs.isEmpty()) return ""
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        fun start(f: File) = try { fmt.parse(f.name.removePrefix("ride_").removeSuffix(".csv"))?.time ?: 0L } catch (_: Exception) { 0L }
        return (logs.firstOrNull { start(it) <= t && t <= it.lastModified() + 60_000 }
            ?: logs.filter { start(it) <= t }.maxByOrNull { start(it) }
            ?: logs.maxBy { start(it) }).name
    }

    private fun save() {
        val d = dir ?: return
        try {
            File(d, "notes.jsonl").writeText(list.value.joinToString("") { n ->
                JSONObject().apply {
                    put("id", n.id); put("written", n.written); put("happened", n.happened ?: JSONObject.NULL)
                    put("agoMin", n.agoMin ?: JSONObject.NULL); put("text", n.text); put("ride", n.ride)
                    put("version", n.version); put("bikeOn", n.bikeOn)
                }.toString() + "\n"
            })
            File(d, "ISSUES.md").writeText(buildString {
                append("# Ronin Dash issues (${list.value.size})\n\n")
                list.value.forEachIndexed { i, n ->
                    append("${i + 1}. ${n.text}\n")
                    append("   - written ${day.format(Date(n.written))}")
                    append(n.happened?.let { ", happened ~${day.format(Date(it))} (${n.agoMin} min before)" } ?: ", time not sure")
                    append("\n   - ride log: ${n.ride.ifEmpty { "none" }} · app ${n.version} · bike ${if (n.bikeOn) "on" else "off"}\n")
                }
            })
        } catch (e: Exception) { android.util.Log.w("RoninDash", "notes save: ${e.message}") }
    }
}
