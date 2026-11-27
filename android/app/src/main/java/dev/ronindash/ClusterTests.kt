package dev.ronindash

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Cluster tests the rider runs from Test & log → Cluster, watching the bike, tapping a mark button when something happens.
 * Every frame sent and every tap goes into the ride log as `[ctest]` with the test's own clock (t = seconds since start),
 * so the timings can be read off the log afterwards. While a test runs the app's automatic brightness changes are paused
 * (frames the official app never sends mid-ride), and Google Maps navigation must be off so only the test talks to the bike.
 * Frames are built exactly like the official app's (checksum included).
 */
object ClusterTests {
    data class Test(val key: String, val title: String, val how: String, val run: Runner.() -> Unit)

    /** What's running: test title + current step label (null = nothing). */
    val now = MutableStateFlow<String?>(null)
    /** Overrides the speed-warning byte of our status frame while a test needs it (null = normal). */
    @Volatile var statusOverspeed: Int? = null
    val running get() = now.value != null

    private val h = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var gen = 0

    private fun t() = "%.1f".format((SystemClock.elapsedRealtime() - startedAt) / 1000.0)
    fun log(s: String) = Bike.log("[ctest] t=${t()} $s")

    /** A tiny timeline: at(seconds) { … }. */
    class Runner(private val g: Int, private val title: String) {
        var end = 0.0
        fun at(s: Double, what: () -> Unit) {
            end = maxOf(end, s)
            h.postDelayed({ if (g == gen) what() }, (s * 1000).toLong())
        }
        fun step(label: String) { now.value = "$title · $label"; log("step $label") }
        /** Direction every [every] s from [from] for [dur] s, its road line [gap] s after each (official: 0.5 / 0.3). */
        fun nav(from: Double, dur: Double, every: Double, next: Int, picto: Int, state: Int, text: String, gap: Double = .3,
                minutes: Int = 10, leftM: Int = 5000) {
            var s = from
            while (s < from + dur - 1e-6) {
                at(s) { send("nav", Protocol.nav(next, minutes, leftM, picto, state)) }
                at(s + gap) { send("nav-text", Protocol.navText(text)) }
                s += every
            }
        }
    }

    private fun send(label: String, f: ByteArray) {
        val svc = Bike.service ?: return log("not connected: $label not sent")
        svc.send("ctest-$label", f)
    }

    val TESTS = listOf(
        Test("arrows", "Arrows", "Ten arrows, 4 s each. The cluster's text line shows the number and name. After each one, tap " +
            "Looks right / Wrong arrow / No arrow (add a note for wrong ones, e.g. \"showed U-turn\").") {
            val list = listOf(Triple(0, 1, "1 LEFT"), Triple(3, 1, "2 RIGHT"), Triple(2, 1, "3 SLIGHT L"), Triple(5, 1, "4 SLIGHT R"),
                Triple(1, 1, "5 SHARP L"), Triple(4, 1, "6 SHARP R"), Triple(41, 0, "7 U-TURN"), Triple(65, 1, "8 ROUNDABOUT"),
                Triple(8, 8, "9 ARRIVED"), Triple(16, 1, "10 ARROW 16"))
            list.forEachIndexed { i, (p, st, label) ->
                val s = i * 5.0
                at(s) { step(label) }
                nav(s, 4.0, .5, 200, p, st, label)
            }
        },
        Test("official", "Official rhythm", "30 s of one direction exactly like the official app: direction every 0.5 s, " +
            "road line 0.3 s after. Tap Flash at each flash. This is what the app now does on a real route.") {
            at(0.0) { step("every 0.5 s") }; nav(0.0, 30.0, .5, 200, 0, 1, "TURN LEFT")
        },
        Test("km", "Where 63 km comes from", "Seven 6 s steps; the cluster's text line names each one. The remaining " +
            "distance is set to 63 km and the time left to 47 min, so if a big number shows you can tell which it is. " +
            "Tap Shows km (and type the number) whenever a km or other big number appears instead of metres.") {
            // ride 3, 15:25: the official app showed "63 km" once, though it sends 0 m hundreds of times without trouble.
            // Each step isolates one thing that was different at that moment.
            val L = 63000; val M = 47
            val steps = listOf<Triple<String, Double, Runner.(Double) -> Unit>>(
                Triple("1 NORMAL 300M", 6.0) { s -> nav(s, 6.0, .5, 300, 0, 1, "1 NORMAL 300M", minutes = M, leftM = L) },
                Triple("2 ZERO M", 6.0) { s -> nav(s, 6.0, .5, 0, 3, 0, "2 ZERO M", minutes = M, leftM = L) },
                Triple("3 REROUTE", 6.0) { s -> nav(s, 6.0, .5, 0, 72, 0, "3 REROUTE", minutes = M, leftM = L) },
                Triple("4 LONG STRAIGHT", 6.0) { s -> nav(s, 6.0, .5, 2000, 7, 5, "4 LONG STRAIGHT", minutes = M, leftM = L) },
                Triple("5 HUGE ROUTE", 6.0) { s -> nav(s, 6.0, .5, 0, 3, 0, "5 HUGE ROUTE", minutes = 3075, leftM = 2844357) },
                // the exact 15:25:06-17 sequence: 250 m left, reroute + pop-up text, 50 m, reroute, 300 m right
                Triple("6 OFFICIAL 1525", 8.0) { s ->
                    nav(s, 1.0, .5, 250, 0, 1, "Turn Left", minutes = M, leftM = L)
                    at(s + 1.0) { send("text", Protocol.text("Re routing")) }
                    nav(s + 1.1, .5, .5, 0, 72, 0, "Re routing", minutes = M, leftM = L)
                    nav(s + 1.6, 1.0, .5, 50, 0, 0, "Turn Left", minutes = M, leftM = L)
                    nav(s + 2.6, .5, .5, 0, 72, 0, "Re routing", minutes = M, leftM = L)
                    nav(s + 3.1, 4.9, .5, 300, 3, 1, "TURN RIGHT", minutes = M, leftM = L)
                },
                Triple("7 NORMAL 300M", 6.0) { s -> nav(s, 6.0, .5, 300, 0, 1, "7 NORMAL 300M", minutes = M, leftM = L) },
            )
            var s = 0.0
            for ((label, dur, run) in steps) { val at0 = s; at(at0) { step(label) }; run(at0); s += dur }
            at(s) { }
        },
        Test("every5", "Refresh every 5 s", "30 s of one direction sent every 5 s. Tap Flash every time the cluster flashes its " +
            "normal screen.") {
            at(0.0) { step("every 5 s") }; nav(0.0, 30.0, 5.0, 200, 0, 1, "TURN LEFT")
        },
        Test("every2", "Refresh every 2 s", "Same, sent every 2 s. Tap Flash at each flash.") {
            at(0.0) { step("every 2 s") }; nav(0.0, 24.0, 2.0, 200, 0, 1, "TURN LEFT")
        },
        Test("every1", "Refresh every 1 s", "Same, every second. Tap Flash at each flash.") {
            at(0.0) { step("every 1 s") }; nav(0.0, 20.0, 1.0, 200, 0, 1, "TURN LEFT")
        },
        Test("hold", "How long it stays", "5 s of a direction, then nothing. Tap Gone the moment the cluster goes back to normal.") {
            at(0.0) { step("sending") }; nav(0.0, 5.0, .5, 200, 0, 1, "TURN LEFT")
            at(5.0) { step("stopped: tap Gone when it goes") }
            at(40.0) { }
        },
        Test("bright", "Brightness during navigation", "A direction (official rhythm) for 24 s; the brightness changes at 8 s and " +
            "again at 16 s. Tap Odd if the cluster shows anything strange (a km number, another screen).") {
            at(0.0) { step("direction only") }; nav(0.0, 24.0, .5, 300, 0, 1, "TURN LEFT")
            val lv = Bike.clusterLevel.value.takeIf { it in 2..10 } ?: 6
            val other = if (lv >= 8) lv - 2 else lv + 2
            at(8.0) { step("brightness → $other"); send("brightness $other", Protocol.brightness(other)) }
            at(16.0) { step("brightness → $lv"); send("brightness $lv", Protocol.brightness(lv)) }
        },
        Test("speed", "Speed-warning number", "A direction (official rhythm) for 30 s while our status says 80, then 100, then 0 in " +
            "the speed-warning slot (10 s each). Tap Odd if you see 50, a call count or anything strange.") {
            at(0.0) { step("warning 80"); statusOverspeed = 80 }
            at(10.0) { step("warning 100"); statusOverspeed = 100 }
            at(20.0) { step("warning 0 (0 = nothing)"); statusOverspeed = 0 }
            var s = 0.0
            while (s < 30.0) { at(s) { Bike.service?.sendStatusNow() }; s += 1.0 }
            nav(0.0, 30.0, .5, 300, 0, 1, "TURN LEFT")
        },
    )

    fun start(test: Test): String? {
        if (Bike.service == null || Bike.conn.value.phase != Phase.LIVE) return "Connect to the bike first"
        if (Bike.nav.value != null) return "Turn Google Maps navigation off first, so only the test talks to the bike"
        stop("restart")
        gen++
        startedAt = SystemClock.elapsedRealtime()
        now.value = test.title
        log("START ${test.title}")
        val r = Runner(gen, test.title).apply(test.run)
        val g = gen
        h.postDelayed({ if (g == gen) stop("done") }, ((r.end + 2) * 1000).toLong())
        return null
    }

    fun stop(why: String) {
        if (!running) return
        gen++
        statusOverspeed = null
        log("END ($why)")
        now.value = null
        Bike.service?.sendStatusNow()
    }

    fun mark(what: String, note: String = "") =
        log("MARK $what" + (now.value?.let { " during [$it]" } ?: "") + if (note.isNotBlank()) " ($note)" else "")
}
