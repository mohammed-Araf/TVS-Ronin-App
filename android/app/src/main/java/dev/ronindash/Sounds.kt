package dev.ronindash

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Helmet sounds (through the intercom). Short earcons first; words only when the Voice setting allows it.
 * - Earcons: res/raw/snd_*.ogg, made by tools/make_sounds.py (stereo: left/right cues play in that ear).
 * - Fixed phrases: res/raw/v_<voice>_<clip>.ogg, Kokoro-82M clips made by tools/make_voice.py. Only the chosen voice is loaded.
 * - Anything else (names): the phone's best offline TTS voice, Indian English first, never a network voice.
 * Played on the navigation-guidance stream with transient "may duck" focus, so music dips for a moment.
 * Only through earphones, a Bluetooth headset or an intercom (wired, USB or Bluetooth), never the phone's own speaker:
 * with nothing connected the sounds are skipped. Settings → Hear the sounds is the one exception (you tapped play).
 */
@SuppressLint("DiscouragedApi")
class Sounds(private val ctx: Context) {
    companion object {
        private val PRIVATE_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_LINE_ANALOG,
        )
        private val NAV_ATTRS = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

        /** Where helmet sounds would go right now: a headset / earphones / intercom name, or null = only the phone speaker. */
        fun privateOutput(ctx: Context): String? {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return null
            val devs = try {
                if (Build.VERSION.SDK_INT >= 33) am.getAudioDevicesForAttributes(NAV_ATTRS)
                else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            } catch (_: Exception) { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            return devs.firstOrNull { it.type in PRIVATE_OUTPUTS }?.let { it.productName?.toString()?.ifBlank { null } ?: "headset" }
        }

        val EARCONS = listOf(
            "arrived", "connected", "disconnected", "engine_start", "engine_warm", "fuel_low", "ind_forgot", "ind_tick_both", "ind_tick_left", "ind_tick_right",
            "ind_tock_both", "ind_tock_left", "ind_tock_right", "keepalive", "message", "missed_call", "reroute", "roundabout", "service", "speed",
            "turn_now_left", "turn_now_right", "turn_soon_left", "turn_soon_right", "turn_soon_straight", "urgent", "uturn", "warning",
        )
        val VOICES = listOf(
            Triple("af_heart", "Heart", "US · female"), Triple("bf_emma", "Emma", "UK · female"),
            Triple("am_michael", "Michael", "US · male"), Triple("bm_george", "George", "UK · male"),
        )
        /** Fixed phrase → clip name (lower case, no trailing punctuation). */
        val CLIPS = mapOf(
            "side stand" to "side_stand", "crash detected. hold the i button if you're ok" to "crash", "sending sos in ten seconds" to "crash_10",
            "sos sent" to "sos_sent", "okay. cancelled" to "crash_ok", "bike down. hold the i button if you're ok" to "bike_down",
            "fuel low" to "fuel_low", "bike lost" to "bike_lost", "bike connected" to "bike_connected", "indicator" to "indicator",
            "check engine" to "check_engine", "service due" to "service_due", "slow down" to "slow_down", "left turn" to "turn_left",
            "right turn" to "turn_right", "keep straight" to "keep_straight", "roundabout" to "roundabout", "u-turn" to "uturn",
            "arrived" to "arrived", "missed call" to "missed_call", "message" to "message",
        )
    }

    private val prefs = Prefs(ctx)
    private val h = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
    private val ids = HashMap<String, Int>()
    private val clipIds = HashMap<String, Int>()
    private var clipVoice = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    private val release = Runnable { am.abandonAudioFocusRequest(focus) }

    init {
        EARCONS.forEach { n -> raw("snd_$n")?.let { ids[n] = pool.load(ctx, it, 1) } }
        loadVoice(prefs.voiceName)
    }

    private fun raw(name: String) = ctx.resources.getIdentifier(name, "raw", ctx.packageName).takeIf { it != 0 }

    /** Decodes only the chosen voice's clips (about 108 KB). */
    fun loadVoice(v: String) {
        if (v == clipVoice) return
        clipIds.values.forEach { pool.unload(it) }; clipIds.clear(); clipVoice = v
        CLIPS.values.toSet().forEach { c -> raw("v_${v}_$c")?.let { clipIds[c] = pool.load(ctx, it, 1) } }
    }

    private var lastOut: Boolean? = null

    /** True when a headset / earphones / intercom is the output; logs once each time that changes. */
    private fun canPlay(preview: Boolean): Boolean {
        if (preview) return true
        val out = privateOutput(ctx)
        val ok = out != null
        if (ok != lastOut) { lastOut = ok; Bike.log(if (ok) "[sound] playing through $out" else "[sound] no earphones / intercom: helmet sounds muted (never the phone speaker)") }
        return ok
    }

    private fun duck(ms: Long) {
        am.requestAudioFocus(focus)
        h.removeCallbacks(release); h.postDelayed(release, ms + 400)
    }

    /** Play one earcon, optionally later. */
    fun play(name: String, delayMs: Long = 0, preview: Boolean = false) {
        val id = ids[name] ?: return
        h.postDelayed({ if (canPlay(preview)) { duck(1500); pool.play(id, 1f, 1f, 1, 0, 1f) } }, delayMs)
    }

    private var awakeStream = 0

    /**
     * While the indicator blinks: loop an inaudible sound so the Bluetooth link to the intercom stays open. Otherwise the
     * link sleeps between sounds and the first part of each one (all of a short tick) is lost while it wakes up.
     */
    fun keepAwake(on: Boolean) {
        if (on && awakeStream == 0) {
            val id = ids["keepalive"] ?: return
            if (canPlay(false)) awakeStream = pool.play(id, 1f, 1f, 0, -1, 1f)
        } else if (!on && awakeStream != 0) { pool.stop(awakeStream); awakeStream = 0 }
    }

    /** A spoken phrase: a pre-rendered clip if we have it, otherwise the phone's best offline voice. */
    fun say(text: String, delayMs: Long = 0, preview: Boolean = false) {
        if (text.isBlank()) return
        loadVoice(prefs.voiceName)
        val clip = CLIPS[text.lowercase(Locale.ROOT).trimEnd('.', '!').trim()]?.let { clipIds[it] }
        if (clip != null) { h.postDelayed({ if (canPlay(preview)) { duck(2500); pool.play(clip, 1f, 1f, 2, 0, 1f) } }, delayMs); return }
        h.postDelayed({ if (canPlay(preview)) speak(text) }, delayMs)
    }

    /**
     * The sound always plays (if its switch is on); words only if Voice allows it:
     * Off = never, Emergencies = only when [emergency], More = short words for everything.
     */
    fun cue(sound: String?, words: String = "", emergency: Boolean = false) {
        if (sound != null) play(sound)
        val v = prefs.voice
        if (words.isNotBlank() && (v == "more" || (v == "emergency" && emergency))) say(words, if (sound != null) 650 else 0)
    }

    private fun speak(text: String) {
        val t = tts
        if (t == null) {
            tts = TextToSpeech(ctx) { st ->
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) { pickVoice(); speak(text) }
            }
            return
        }
        if (!ttsReady) return
        duck(2500)
        t.setAudioAttributes(attrs)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "rd${System.nanoTime()}")
    }

    /** Highest-quality offline English voice; Indian English first. */
    private fun pickVoice() {
        val t = tts ?: return
        val best = try {
            t.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && "notInstalled" !in it.features }
                .maxByOrNull { v -> v.quality * 10 + (if (v.locale.country == "IN") 5 else if (v.locale.country == "GB") 2 else 0) }
        } catch (_: Exception) { null }
        if (best != null) t.voice = best
        else t.language = Locale("en", "IN")
    }

    fun release() {
        h.removeCallbacksAndMessages(null)
        am.abandonAudioFocusRequest(focus)
        pool.release(); tts?.shutdown(); tts = null
    }
}

/** Sound demo list for Settings → Hear the sounds: name, then (earcon, delay ms) steps. */
val SOUND_DEMO = listOf(
    "Indicator, left" to listOf("ind_tick_left" to 0L, "ind_tock_left" to 360L, "ind_tick_left" to 720L, "ind_tock_left" to 1080L),
    "Indicator, right" to listOf("ind_tick_right" to 0L, "ind_tock_right" to 360L, "ind_tick_right" to 720L, "ind_tock_right" to 1080L),
    "Hazard" to listOf("ind_tick_both" to 0L, "ind_tock_both" to 360L, "ind_tick_both" to 720L, "ind_tock_both" to 1080L),
    "Indicator left on" to listOf("ind_forgot" to 0L), "Turn left soon" to listOf("turn_soon_left" to 0L),
    "Turn left now" to listOf("turn_now_left" to 0L), "Turn right soon" to listOf("turn_soon_right" to 0L),
    "Turn right now" to listOf("turn_now_right" to 0L), "Keep straight" to listOf("turn_soon_straight" to 0L),
    "Roundabout" to listOf("roundabout" to 0L), "U-turn" to listOf("uturn" to 0L), "Re-routing" to listOf("reroute" to 0L),
    "Arrived" to listOf("arrived" to 0L), "Over the limit" to listOf("speed" to 0L), "Fuel low" to listOf("fuel_low" to 0L),
    "Warning lamp" to listOf("warning" to 0L), "Service due" to listOf("service" to 0L), "Engine warmed up" to listOf("engine_warm" to 0L), "Engine start (app opening)" to listOf("engine_start" to 0L),
    "Emergency" to listOf("urgent" to 0L), "Missed call" to listOf("missed_call" to 0L), "Message" to listOf("message" to 0L),
    "Bike connected" to listOf("connected" to 0L), "Bike lost" to listOf("disconnected" to 0L),
)
