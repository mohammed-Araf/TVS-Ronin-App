package dev.ronindash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The ride service. Android's companion-device watch starts it when the bike turns on (CompanionService) and it stops itself
 * when the bike goes away, so nothing runs while the bike is off. While it runs:
 * - one GATT write at a time; fast direct reconnect when the link drops mid-ride, then the display is put back as it was
 * - every bike value through the data guard; alerts act only on 2+ agreeing readings
 * - status every 2 s (clock, real missed calls / unread messages, no stuck voice-assist state)
 * - I button on its rising edge: tap = reject, hold 2–3 s and let go = answer / assistant
 * - caller at once (≤ 3 times, never during an answered call); calls and messages held while riding fast
 * - turns on the bike only when close, helmet sounds, fuel model, warm-up, screens, bike display brightness
 */
@SuppressLint("MissingPermission")
class BikeService : Service() {

    companion object {
        private const val CH = "ride"
        private const val NOTIF_ID = 1
        fun start(ctx: Context) = try { ctx.startForegroundService(Intent(ctx, BikeService::class.java)); true } catch (e: Exception) {
            Bike.log("Can't start the ride service: ${e.message}"); false
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, BikeService::class.java))
        val running get() = Bike.service != null
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<Pair<String, ByteArray>>()
    private var writing = false
    private var ready = false
    private var stopping = false
    private var retry = 0
    private var everLive = false
    private var lastCode = 0
    private var callState: String = TelephonyManager.EXTRA_STATE_IDLE
    private var callerName: String? = null
    private var callerSends = 0
    private var vaUntil = 0L
    private var lastAssistAt = 0L
    /** Seen ~2.5 s into a hold; kept 3 s more (≈6 s in all) = long. */
    private val starterTap = TapDetector(longMs = 3000, doubles = false)
    private val modeBtn = ModeDetector()
    private var engineOnAt = 0L
    private var lastStarterBit = false
    private var tick = 0
    private var csvWriter: FileWriter? = null
    private val lastByType = HashMap<Int, ByteArray>()
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    lateinit var crash: CrashGuard; private set
    private lateinit var trips: TripRecorder
    private lateinit var fuel: FuelModel
    lateinit var sounds: Sounds; private set
    private lateinit var sensors: PhoneSensors
    private val guard = DataGuard()
    private var loc: Location? = null
    private var gpsOn = false
    private var gpsEvery = 0L
    private var shutdownSeen = false
    private var battAtStart = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Bike.service = this
        val nm = getSystemService(NotificationManager::class.java)
        // Android requires a notification while the service runs: MIN importance = no status-bar icon, no sound, collapsed.
        nm.createNotificationChannel(NotificationChannel(CH, "While riding", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        nm.deleteNotificationChannel("bike")
        sounds = Sounds(this)
        crash = CrashGuard(this, h, { l, f -> send(l, f) }, { snd, words -> sounds.cue(snd, words, emergency = true) })
        val store = object : FuelModel.Store {
            override var fuelCal by prefs::fuelCal; override var fulls by prefs::fulls; override var recKm by prefs::recKm
            override var recL by prefs::recL; override var tankLeftL by prefs::tankLeftL; override var burnRaw by prefs::burnRaw
            override var poured by prefs::poured; override var fullKnown by prefs::fullKnown; override var speedoK by prefs::speedoK
            override val tankL get() = prefs.tankL; override val fuelPrice get() = prefs.fuelPrice
            override var ml by prefs::fuelMl; override val warmC get() = prefs.warmC
            override var gauge by prefs::fuelGauge; override var odo by prefs::fuelOdo
        }
        fuel = FuelModel(store).also { it.onLog = { m -> Bike.log(m) } }
        trips = TripRecorder(this) { fuel.newTrip(); Bike.lean.value = Lean() }
        trips.resume()
        sensors = PhoneSensors(this)
        try {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (has(Manifest.permission.ACCESS_FINE_LOCATION)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIF_ID, notif("Riding"), type)
        } catch (e: Exception) {
            Bike.conn.value = Conn(Phase.PROBLEM, "Can't run in background: ${e.message}")
            stopSelf(); return
        }
        if (prefs.fileLog) openCsv()
        registerReceiver(phoneReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), RECEIVER_EXPORTED)
        refreshMissed()
        if (prefs.lastOdo > 0) Bike.serviceLeftKm.value = (prefs.nextServiceAt(prefs.lastOdo) - prefs.lastOdo).toInt()
        connect(auto = false)
        h.postDelayed(statusTick, 2000)
        h.postDelayed(secondTick, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        stopping = true
        h.removeCallbacksAndMessages(null)
        val s = Bike.state.value
        if (::trips.isInitialized) trips.park(s)
        if (::fuel.isInitialized) { fuel.save(); if (s.fuelBars > 0) prefs.lastBars = s.fuelBars }
        if (::crash.isInitialized) crash.stop()
        if (::sensors.isInitialized) sensors.basic(false)
        postServiceDue()
        gps(false)
        try { unregisterReceiver(phoneReceiver) } catch (_: Exception) {}
        closeGatt()
        if (::sounds.isInitialized) sounds.release()
        csvWriter?.close(); csvWriter = null
        Bike.service = null
        Bike.conn.value = Conn(Phase.OFF, "Bike off · connects by itself when you turn it on")
        Bike.warmup.value = null; Bike.standAlarm.value = false; Bike.turn.value = null; Bike.hazard.value = false; Bike.indicator.value = null; Bike.gtt.value = false
        super.onDestroy()
    }

    // ================================================================= connection

    private fun setConn(p: Phase, d: String) {
        if (Bike.conn.value == Conn(p, d)) return
        Bike.conn.value = Conn(p, d)
        Bike.log("[conn] $d")
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle("Ronin Dash")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun connect(auto: Boolean) {
        if (stopping) return
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) { setConn(Phase.PROBLEM, "Bluetooth is off"); retryLater(); return }
        val mac = prefs.mac ?: try {
            adapter.bondedDevices.firstOrNull { it.name?.startsWith("TVS") == true }?.also { prefs.mac = it.address; prefs.bikeName = it.name ?: "" }?.address
        } catch (_: SecurityException) { null }
        if (mac == null) { setConn(Phase.PROBLEM, "No bike chosen. Open Settings."); return }
        val dev = adapter.getRemoteDevice(mac)
        if (dev.bondState != BluetoothDevice.BOND_BONDED) {
            setConn(Phase.PROBLEM, "Bike not paired. Open Settings → Bike")
            retryLater(); return
        }
        closeGatt()
        setConn(if (auto) Phase.WAITING else Phase.CONNECTING, if (auto) "Waiting for the bike (ignition on?)" else "Connecting…")
        gatt = dev.connectGatt(this, auto, cb, BluetoothDevice.TRANSPORT_LE)
        if (!auto) { h.removeCallbacks(directTimeout); h.postDelayed(directTimeout, 8000) }
    }

    /** A direct connect that hasn't come up in 8 s falls back to waiting quietly (autoConnect). */
    private val directTimeout = Runnable { if (!ready && !stopping) { Bike.log("[conn] direct connect timed out"); connect(auto = true) } }

    private fun retryLater() {
        if (stopping) return
        val delay = (2000L shl retry.coerceAtMost(4)).coerceAtMost(30_000L)
        retry++
        h.removeCallbacks(retryRun)
        h.postDelayed(retryRun, delay)
    }

    private val retryRun = Runnable { connect(auto = retry > 2) }

    private fun closeGatt() {
        ready = false; writing = false; queue.clear(); tx = null; lastCode = 0
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
    }

    /** Drop the link and connect again (e.g. after choosing a different bike). */
    fun reconnect() = h.post { retry = 0; connect(auto = false) }

    /** With a companion association Android wakes us when the bike is back, so we can stop; without one we keep waiting. */
    private val goAway = Runnable {
        if (prefs.associationId >= 0 && !ready) { Bike.log("[conn] bike off → saving and stopping (Android wakes us next time)"); stopSelf() }
    }

    fun bikeGone() = h.post { if (!ready) { h.removeCallbacks(goAway); h.post(goAway) } }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = h.post {
            if (g != gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                h.removeCallbacks(directTimeout)
                setConn(Phase.CONNECTING, "Connected, reading bike…")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasLive = ready
                Bike.log("[conn] link lost, status=$status")
                closeGatt()
                if (stopping) return@post
                if (shutdownSeen) {
                    // The bike told us it's switching off (byte 11 = 0x80): save now, don't wait for timeouts.
                    setConn(Phase.OFF, "Bike off · connects by itself when you turn it on")
                    Bike.closeDash.value = true
                    h.postDelayed(goAway, 1500)
                    if (prefs.associationId < 0) h.postDelayed({ connect(auto = true) }, 1000)
                    return@post
                }
                if (wasLive) {
                    // Mid-ride drop (e.g. answering a call through the intercom took the link for 6 s): reconnect directly at once.
                    if (Bike.state.value.speed > 0 && prefs.sndWarn) sounds.cue("disconnected", "Bike lost")
                    connect(auto = false)
                } else {
                    setConn(Phase.WAITING, "Waiting for the bike (ignition on?)")
                    h.postDelayed({ connect(auto = true) }, 1000)
                }
                h.removeCallbacks(goAway); h.postDelayed(goAway, 90_000)
            }
        }.let { }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = h.post {
            val svc = g.getService(Protocol.SERVICE)
            if (svc == null) { setConn(Phase.PROBLEM, "This doesn't look like a Ronin cluster"); return@post }
            tx = svc.getCharacteristic(Protocol.TX)
            val rx = svc.getCharacteristic(Protocol.RX)
            if (tx == null || rx == null) { setConn(Phase.PROBLEM, "Bike characteristics missing"); return@post }
            g.setCharacteristicNotification(rx, true)
            val d = rx.getDescriptor(Protocol.CCCD)
            val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v)
            else @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
        }.let { }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = h.post {
            if (status != BluetoothGatt.GATT_SUCCESS) { setConn(Phase.PROBLEM, "Bike refused (status $status). Re-pair?"); return@post }
            ready = true; retry = 0; shutdownSeen = false
            h.removeCallbacks(goAway)
            setConn(Phase.LIVE, "Connected to ${prefs.bikeName.ifBlank { "bike" }}")
            onReady()
        }.let { }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf(); h.post { onFrame(copy) }
        }

        @Deprecated("pre-33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: return
            h.post { onFrame(v) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = h.post {
            if (status != BluetoothGatt.GATT_SUCCESS) Bike.log("write failed, status=$status")
            writing = false; h.removeCallbacks(writeTimeout); pump()
        }.let { }
    }

    /**
     * After any (re)connect, put the bike display back as it was: greeting the first time, then the caller if a call is
     * ringing (never during an answered call), the current turn if it's close, and missed counts + time via the status frame.
     */
    private fun onReady() {
        val first = !everLive
        everLive = true
        Bike.closeDash.value = false
        sensors.basic(true)
        if (first) {
            val name = Protocol.clean(prefs.riderName.replace(" ", ""))
            if (name.isNotEmpty()) send("name", Protocol.riderName(name))
            battAtStart = batteryPct()
            if (prefs.sndWarn) sounds.play("connected")
            h.postDelayed({ maybeOpenDashboard() }, 1500)
        }
        sendStatus()
        if (callState == TelephonyManager.EXTRA_STATE_RINGING) callerName?.let { send("caller", Protocol.caller(it)) }
        lastNavSent = 0; lastNavKey = ""
        Bike.nav.value?.let { onNav(it) }
    }

    // ================================================================= writes

    private val writeTimeout = Runnable { writing = false; pump() }

    /** Queue a plain frame; it's XOR-encoded and written when the previous write has finished. */
    fun send(label: String, plain: ByteArray) = h.post {
        if (!ready) return@post
        if (label == "status") queue.removeAll { it.first == "status" } // only the newest status matters
        queue.addLast(label to plain)
        while (queue.size > 30) queue.removeFirst()
        pump()
    }

    private fun pump() {
        if (writing || !ready) return
        val (label, plain) = queue.removeFirstOrNull() ?: return
        val g = gatt ?: return
        val c = tx ?: return
        val enc = Protocol.xor(plain)
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, enc, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else @Suppress("DEPRECATION") run {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = enc; g.writeCharacteristic(c)
        }
        csv("TX", Protocol.hex(enc), Protocol.hex(plain), label)
        if (label != "status") Bike.log("→ $label ${Protocol.hex(plain)}")
        if (!ok) { Bike.log("write not accepted ($label)"); h.postDelayed({ pump() }, 200); return }
        writing = true
        h.postDelayed(writeTimeout, 1500)
    }

    private val statusTick = object : Runnable {
        override fun run() {
            tick++
            if (tick % 15 == 0) refreshMissed()
            if (ready) sendStatus()
            if (ready && Bike.state.value.lastFrameMs > 0 && System.currentTimeMillis() - Bike.state.value.lastFrameMs > 10_000) {
                setConn(Phase.PROBLEM, "Connected but bike is silent")
            }
            h.postDelayed(this, 2000)
        }
    }

    private fun batteryPct() = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun sendStatus() {
        val batt = ((batteryPct() + 12) / 25).coerceIn(0, 4)
        val va = if (System.currentTimeMillis() < vaUntil) 32 else 0
        val os = if (prefs.speedAlert && prefs.clusterOverspeed) prefs.speedLimit else 120
        send("status", Protocol.status(Bike.missedCalls.value, Bike.unreadMessages.value, va, 4, batt, os))
    }

    // ================================================================= frames from the bike

    private var standReadings = 0
    private var lampOnReadings = 0
    private var lastEngineOn = false

    private fun onFrame(raw: ByteArray) {
        val f = Protocol.xor(raw)
        val zero = raw.all { it.toInt() == 0 }
        val ok = f.size == 20 && Protocol.checksumOk(f)
        Bike.state.value = Bike.state.value.let {
            it.copy(frames = it.frames + 1, zeroFrames = it.zeroFrames + if (zero) 1 else 0,
                lastFrameMs = if (ok) System.currentTimeMillis() else it.lastFrameMs)
        }
        csv("RX", Protocol.hex(raw), Protocol.hex(f), if (ok) "ok" else "bad")
        if (zero && Bike.state.value.zeroFrames == 20) {
            setConn(Phase.PROBLEM, "Bike sends only zeros: not paired. Re-pair in Settings.")
        }
        if (!ok || f[0] != 0x5A.toByte()) return
        if (Bike.conn.value.phase != Phase.LIVE) setConn(Phase.LIVE, "Connected to ${prefs.bikeName.ifBlank { "bike" }}")
        val u = IntArray(20) { f[it].toInt() and 0xFF }
        val prevState = Bike.state.value
        val g = guard.frame(u, SystemClock.elapsedRealtime() / 1000.0)
        Bike.state.value = prevState.update(u).guarded(g)
        val s = Bike.state.value
        val prev = lastByType[u[1]]
        if (prev == null || !prev.contentEquals(f)) {
            lastByType[u[1]] = f
            Bike.log("← ${Protocol.describe(u)}")
            if (BuildConfig.DEBUG) android.util.Log.d("RoninRaw", Protocol.hex(f))
        }
        when (u[1]) {
            0x10 -> {
                onButtons(u[11])
                onModeCode(u[11] and 0x07)
                if (s.shuttingDown && !shutdownSeen) { shutdownSeen = true; Bike.log("[conn] bike is switching off (byte 11 = 0x80): saving"); trips.park(s); fuel.save() }
                checkSpeed(s.speed); checkService(s.odoKm)
                if (s.fuelBars != prevState.fuelBars || prevState.frames <= 1) onFuelBars(prevState.fuelBars, s.fuelBars)
            }
            0x11 -> {
                onIndicators(s)
                // Side stand down while moving: the stand cuts the engine, so it's the emergency, never "parked". 2 readings agree.
                standReadings = if (s.sideStand && s.speed > 3) standReadings + 1 else 0
                if (standReadings == 2) {
                    Bike.standAlarm.value = true; Bike.log("[alarm] side stand down while moving")
                    if (prefs.sndWarn) sounds.cue("urgent", "Side stand", emergency = true)
                }
                if (standReadings == 0 && Bike.standAlarm.value) Bike.standAlarm.value = false
                // A warning lamp that comes on (2 readings) while riding: one sound.
                lampOnReadings = if (s.milLamp || s.absLamp) lampOnReadings + 1 else 0
                if (lampOnReadings == 2 && s.engineOn && prefs.sndWarn) sounds.cue("warning", "Check engine")
                onClusterLevel(s.clusterLevel)
                if (s.engineOn != lastEngineOn) {
                    if (s.engineOn) { engineOnAt = SystemClock.elapsedRealtime(); onEngineStart(s) } else { Bike.warmup.value = null; starterTap.reset() }
                    lastEngineOn = s.engineOn
                }
                onStarterAndMode(s)
            }
            0x18 -> onTemp(s)
        }
        if (u[1] == 0x11 || u[1] == 0x10) crash.onBike(s, true)
    }

    /** Act once when a button code first appears. The bike repeats it for ~5 s; we ignore the repeats. */
    private fun onButtons(b: Int) {
        val code = b and 0x07
        if (code == lastCode) return
        lastCode = code
        if (code == 0) return
        if (Bike.sos.value != null) { crash.cancel("bike button"); sounds.cue(null, "Okay. Cancelled", emergency = true); return }
        Bike.log("[button] code=$code (byte 0x%02X) during call state $callState".format(b))
        // The bike reports a press when you LET GO: no call → a 2–3 s hold = assistant (4). Ringing: tap = reject (2), hold ~2.5 s = answer (1).
        when (code) {
            1 -> if (callState == TelephonyManager.EXTRA_STATE_RINGING) acceptCall()
            2 -> when (callState) {
                TelephonyManager.EXTRA_STATE_RINGING -> endCall("reject")
                TelephonyManager.EXTRA_STATE_OFFHOOK -> endCall("hang up")
                else -> clearDisplay()
            }
            3 -> {} // mode button: the bike handles riding modes itself
            // On a call (yours going out, or answered): the hold is the only press the bike reports with no call pop-up on
            // the cluster, so it hangs up. Outgoing calls never put the bike in call mode (ride 2026-11-27: couldn't hang up).
            4 -> if (callState == TelephonyManager.EXTRA_STATE_OFFHOOK) endCall("hang up (I hold)")
            // One action per press: ignore repeats for 10 s so the assistant never keeps popping up.
            else if (callState == TelephonyManager.EXTRA_STATE_IDLE && System.currentTimeMillis() - lastAssistAt > 10_000) {
                lastAssistAt = System.currentTimeMillis()
                doAction(BtnAction.of(prefs.actIHold), "I hold")
            }
        }
    }

    // ================================================================= extra button gestures (Buttons.kt)

    /** Engine running for 5 s (and turning), so the press that started it, or a stall restart, is never a gesture. */
    private fun engineSettled(s: BikeState) = s.engineOn && engineOnAt > 0 &&
        SystemClock.elapsedRealtime() - engineOnAt >= 5000 && (s.rpm == 0 || s.rpm >= 500)

    private fun gesturesAllowed() = callState == TelephonyManager.EXTRA_STATE_IDLE && Bike.sos.value == null

    private val starterPoll = Runnable { pollStarter() }

    private fun onStarterAndMode(s: BikeState) {
        val now = SystemClock.elapsedRealtime()
        val ok = gesturesAllowed()
        if (s.starter && !lastStarterBit) Bike.starterSeen.value++
        lastStarterBit = s.starter
        if (prefs.starterOn) {
            val g = starterTap.frame(s.starter, engineSettled(s) && ok, now)
            if (starterTap.note.isNotEmpty()) Bike.log("[btn] starter ${starterTap.note} (rpm ${s.rpm})")
            g?.let { starterGesture(it) }
            h.removeCallbacks(starterPoll)
            if (starterTap.deadline > 0) h.postDelayed(starterPoll, (starterTap.deadline - now).coerceAtLeast(0) + 20)
        }
        if (prefs.modeOn) {
            val g = modeBtn.mode(s.rideMode, s.engineOn && ok, now)
            if (modeBtn.note.isNotEmpty()) Bike.log("[btn] mode ${modeBtn.note}")
            g?.let { modeGesture(it) }
        }
    }

    private fun onModeCode(c: Int) {
        if (!prefs.modeOn) return
        val g = modeBtn.code(c, Bike.state.value.engineOn && gesturesAllowed(), SystemClock.elapsedRealtime())
        if (modeBtn.note.isNotEmpty()) Bike.log("[btn] mode ${modeBtn.note}")
        g?.let { modeGesture(it) }
    }

    private fun pollStarter() { starterTap.poll(SystemClock.elapsedRealtime())?.let { starterGesture(it) } }

    private fun starterGesture(g: Gesture) = doAction(BtnAction.of(when (g) {
        Gesture.SINGLE -> prefs.actStarter1; Gesture.DOUBLE -> prefs.actStarter2; Gesture.LONG -> prefs.actStarterLong
    }), "starter ${g.name.lowercase()}")

    private fun modeGesture(g: Gesture) = doAction(BtnAction.of(when (g) {
        Gesture.DOUBLE -> prefs.actModeDouble; Gesture.LONG -> prefs.actModeLong; Gesture.SINGLE -> "none"
    }), "mode ${g.name.lowercase()}")

    /** Runs a button action. Also used by Settings → "Try" so each action can be checked without the bike. */
    fun doAction(a: BtnAction, from: String) = h.post {
        Bike.log("[btn] $from → ${a.key}")
        Bike.lastBtn.value = "$from → ${a.label}"
        val am = getSystemService(AudioManager::class.java)
        fun key(code: Int) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        fun vol(dir: Int) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
            Bike.log("[btn] music volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" +
                if (am.isStreamMute(AudioManager.STREAM_MUSIC)) " (muted)" else "")
        }
        when (a) {
            BtnAction.NONE -> {}
            BtnAction.NEXT -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
            BtnAction.PREV -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            BtnAction.PLAY -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            BtnAction.VOL_UP -> vol(AudioManager.ADJUST_RAISE)
            BtnAction.VOL_DOWN -> vol(AudioManager.ADJUST_LOWER)
            BtnAction.MUTE -> vol(AudioManager.ADJUST_TOGGLE_MUTE)
            BtnAction.MAP -> openMap()
            BtnAction.DASH -> openApp(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), "dashboard")
            BtnAction.ASSIST -> launchAssistant()
        }
    }

    /** Brings Google Maps to the front (straight back into navigation if it's running); any map app if Maps isn't installed. */
    private fun openMap() {
        val maps = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        openApp((maps ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "map")
    }

    private fun openApp(i: Intent, what: String) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Bike.log("[btn] opened $what") }
        catch (e: Exception) { Bike.log("[btn] can't open $what: ${e.message}. Allow 'Display over other apps' in Settings.") }
    }

    // ================================================================= I-button actions

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun acceptCall() {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return Bike.log("Can't answer: phone permission missing")
        getSystemService(TelecomManager::class.java).acceptRingingCall()
        Bike.log("[call] answered from I button")
    }

    private fun endCall(what: String) {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return Bike.log("Can't $what: phone permission missing")
        @Suppress("DEPRECATION") val r = getSystemService(TelecomManager::class.java).endCall()
        Bike.log("[call] $what from I button: $r")
    }

    fun clearDisplay() { h.removeCallbacks(autoClear); send("clear", Protocol.text("")) }

    /** Pop-ups we put on the cluster (SMS, service, test text) close by themselves after a few seconds. */
    private val autoClear = Runnable { if (Bike.sos.value == null) send("clear", Protocol.text("")) }
    private fun clearLater(ms: Long = 8000) { h.removeCallbacks(autoClear); h.postDelayed(autoClear, ms) }

    fun launchAssistant() = h.post {
        val intent = when (prefs.assist) {
            "none" -> return@post
            "gemini" -> packageManager.getLaunchIntentForPackage("com.google.android.apps.bard") ?: Intent(Intent.ACTION_VOICE_COMMAND)
            else -> Intent(Intent.ACTION_VOICE_COMMAND)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            Bike.log("[assist] opened ${prefs.assist}")
            lastAssistAt = System.currentTimeMillis()
            if (prefs.vaFeedback) {
                // "Listening" shows on the bike for 3 s, then we send idle so the pop-up closes on its own.
                vaUntil = lastAssistAt + 3000; sendStatus()
                h.postDelayed({ sendStatus() }, 3100)
            }
        } catch (e: Exception) {
            Bike.log("[assist] blocked: ${e.message}. Allow 'Display over other apps' in Settings.")
        }
    }

    // ================================================================= calls and messages

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION") val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            h.post { onCallState(st, num) }
        }
    }

    /** Calls and messages held while faster than the slow speed. */
    private val held = ArrayList<Alert>()
    private fun holding() = prefs.holdAlerts && ready && Bike.state.value.speed >= prefs.slowKmh

    private fun onCallState(st: String, num: String?) {
        val prev = callState
        callState = st
        if (prev != st) Bike.log("[call] $prev → $st")
        when (st) {
            TelephonyManager.EXTRA_STATE_RINGING -> if (num != null && prefs.calls && callerName == null) {
                // We read the phone's call state ourselves (the TVS app is ~4.5 s late), so the caller is on the bike at once and a
                // hold of the I button can answer before an intercom's auto-answer.
                val who = lookupName(num) ?: num.replace(Regex("^\\+91"), "").filter { it.isDigit() || it == '+' }
                callerName = Protocol.clean(who)
                if (holding()) { held += Alert(true, who); Bike.log("[hold] call from $who at ${Bike.state.value.speed} km/h (rings in the helmet)"); return }
                // 99 guard: at most 3 caller frames while ringing (the official app sends 11), never once answered.
                callerSends = 0
                fun again() {
                    if (callState != TelephonyManager.EXTRA_STATE_RINGING || callerSends >= 3) return
                    callerSends++; callerName?.let { send("caller", Protocol.caller(it)) }
                    h.postDelayed({ again() }, 700)
                }
                again()
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> if (prev == TelephonyManager.EXTRA_STATE_RINGING) {
                // Answered (helmet, phone or bike): close the caller pop-up now, not only when the call ends.
                callerSends = 3; clearDisplay()
            } else if (prev == TelephonyManager.EXTRA_STATE_IDLE && prefs.calls && ready) {
                // A call you made: the bike has no outgoing-call screen, so say so for a moment; hold I to hang up.
                Bike.log("[call] outgoing call: hold I to hang up")
                send("calling", Protocol.text("CALLING")); clearLater(4000)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (prev == TelephonyManager.EXTRA_STATE_RINGING) clearLater(1500) // missed or rejected: close the caller pop-up
                callerName = null
                h.postDelayed({ refreshMissed(); sendStatus() }, 1500)
            }
        }
    }

    private fun lookupName(num: String): String? {
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
        return try {
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    /** Real missed-call count: missed calls the user hasn't looked at yet. */
    private fun refreshMissed() {
        if (!has(Manifest.permission.READ_CALL_LOG)) return
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
                "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.NEW}=1", arrayOf(CallLog.Calls.MISSED_TYPE.toString()), "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val who = mutableListOf<String>()
                while (c.moveToNext()) who += (c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getString(1) ?: "Unknown")
                Bike.missedCalls.value = who.size
                Bike.missedFrom.value = who.distinct()
            }
        } catch (_: Exception) {}
    }

    fun onMessage(sender: String) = h.post {
        if (holding()) { held += Alert(false, sender); Bike.log("[hold] message from $sender"); return@post }
        deliverMessage(sender)
        if (prefs.sndMissed && ready) sounds.play("message")
        Bike.released.value = (Bike.released.value + Alert(false, sender)).takeLast(20)
    }

    private fun deliverMessage(sender: String) {
        val s = Protocol.clean(sender, 16)
        if (s.isNotEmpty()) { send("sms", Protocol.sms(s)); clearLater() }
        sendStatus()
    }

    /** Slowed down (or parked): what was held comes through, with one chirp per call and one pop per message (max 3 each). */
    private fun releaseHeld() {
        if (held.isEmpty()) return
        Bike.log("[hold] slowed to ${Bike.state.value.speed} km/h → releasing ${held.size}")
        val calls = held.filter { it.call }; val msgs = held.filter { !it.call }
        if (prefs.sndMissed) {
            var t = 0L
            calls.take(3).forEach { sounds.play("missed_call", t); t += 550 }
            msgs.take(3).forEach { sounds.play("message", t); t += 550 }
            if (prefs.voice == "more") sounds.say(held.joinToString(". ") { (if (it.call) "Call, " else "Message, ") + it.who }, t + 300)
        }
        msgs.lastOrNull()?.let { deliverMessage(it.who) }
        Bike.released.value = (Bike.released.value + held).takeLast(20)
        held.clear()
        refreshMissed(); sendStatus()
    }

    // ================================================================= navigation

    private var navActive = false
    private var lastNavKey = ""
    private var lastNavSent = 0L
    private var navMeters = -1.0
    private var navPrevM = 99999.0
    private var navDir = Dir.UNKNOWN
    private var turnPoint: Pair<Double, Double>? = null
    private var turnShown = false

    /** Maps moved on (new instruction or distance). The turn shows up ~36 s before it; on the bike only while close. */
    fun onNav(n: NavInfo?) = h.post {
        if (!prefs.navOn) return@post
        if (n == null) {
            if (navActive) send("nav-end", Protocol.nav(0, 0, 0, 7, 7))
            navActive = false; lastNavKey = ""; navMeters = -1.0; Bike.turn.value = null; turnPoint = null; turnShown = false
            return@post
        }
        val newTurn = n.dir != navDir || n.nextM > navMeters + 150
        if (newTurn) { turnShown = false; turnPoint = null; navPrevM = 99999.0 }
        navDir = n.dir; navMeters = n.nextM.toDouble()
        // where the turn is: projected ahead along the heading, corrected with every Maps update
        loc?.let { l -> heading()?.let { hd -> val p = project(l.latitude, l.longitude, hd.toDouble(), navMeters)
            turnPoint = turnPoint?.let { (it.first + p.first) / 2 to (it.second + p.second) / 2 } ?: p } }
        updateTurn(sendNow = true)
    }

    /** About 36 s ahead: up to 1 km at 100 km/h, ~300 m at 30 km/h, never under 200 m. */
    private fun turnWindow() = max(200.0, min(1000.0, Bike.state.value.speed / 3.6 * 36))

    private fun updateTurn(sendNow: Boolean) {
        if (navMeters < 0) return
        val m = navMeters
        if (!turnShown && m <= turnWindow()) turnShown = true
        val near = turnShown && m <= 1100
        Bike.turn.value = if (turnShown && prefs.navOn) Turn(navDir, roundM(m), turnAngle(), m <= 120) else null
        // helmet tones: two notes at 300 m, three at the turn, in the ear you turn to (Google's own voice keeps working)
        if (ready && prefs.sndTurns) {
            val (soon, now) = turnSounds(navDir)
            if (navPrevM > 300 && m <= 300 && soon != null) sounds.cue(soon, if (prefs.sayTurns) turnWord(navDir) else "")
            if (navPrevM > 50 && m <= 50 && now != null) sounds.play(now)
        }
        navPrevM = m
        // On the bike only when close, then the display goes back to normal so a long press reaches us between turns.
        if (!sendNow && !near) return
        if (!near) { if (navActive) { send("nav-end", Protocol.nav(0, 0, 0, 7, 7)); navActive = false }; return }
        val n = Bike.nav.value ?: return
        val next = if (m >= 100) ((m + 25) / 50).toInt() * 50 else m.toInt()
        val key = "$next|${n.dir}|${n.road}|${n.minutesLeft}"
        val t = System.currentTimeMillis()
        if (key == lastNavKey && t - lastNavSent < 5000) return
        lastNavKey = key; lastNavSent = t; navActive = true
        val (picto, state) = when {
            n.dir == Dir.ARRIVE -> 7 to 8
            n.dir == Dir.UTURN -> 41 to 0
            n.isLeft && next < 65 -> 7 to 3
            n.isRight && next < 65 -> 7 to 4
            next >= 1000 -> 7 to 5
            else -> 7 to 1
        }
        val sendNext = if (n.dir == Dir.ARRIVE || n.dir == Dir.UTURN) 0 else next
        send("nav", Protocol.nav(sendNext, n.minutesLeft, n.leftM, picto, state))
        val w = NavParser.word(n.dir)
        send("nav-text", Protocol.navText(Protocol.clean(if (n.road.isBlank()) w else "$w ${n.road}".trim())))
    }

    private fun roundM(m: Double) = if (m >= 100) (m / 10).roundToInt() * 10 else max(0, m.roundToInt())

    private fun turnSounds(d: Dir): Pair<String?, String?> = when (d) {
        Dir.LEFT, Dir.SHARP_LEFT, Dir.SLIGHT_LEFT -> "turn_soon_left" to "turn_now_left"
        Dir.RIGHT, Dir.SHARP_RIGHT, Dir.SLIGHT_RIGHT -> "turn_soon_right" to "turn_now_right"
        Dir.ROUNDABOUT -> "roundabout" to "roundabout"
        Dir.UTURN -> "uturn" to null
        Dir.ARRIVE -> null to "arrived"
        Dir.STRAIGHT -> "turn_soon_straight" to null
        Dir.UNKNOWN -> null to null
    }

    private fun turnWord(d: Dir) = when (d) {
        Dir.LEFT, Dir.SHARP_LEFT, Dir.SLIGHT_LEFT -> "Left turn"; Dir.RIGHT, Dir.SHARP_RIGHT, Dir.SLIGHT_RIGHT -> "Right turn"
        Dir.ROUNDABOUT -> "Roundabout"; Dir.UTURN -> "U-turn"; Dir.STRAIGHT -> "Keep straight"; Dir.ARRIVE -> "Arrived"; else -> ""
    }

    /** GPS heading while moving, the corrected compass below ~15 km/h. */
    private fun heading(): Float? {
        val l = loc
        if (l != null && l.hasBearing() && l.speed * 3.6 > 15 && System.currentTimeMillis() - l.time < 6000) return l.bearing
        return sensors.heading.takeIf { !it.isNaN() }
    }

    /** Compass angle: points at the estimated turn spot, then swings to the exit direction in the last ~120 m. */
    private fun turnAngle(): Float {
        val exitA = when (navDir) {
            Dir.LEFT, Dir.SHARP_LEFT -> -90f; Dir.SLIGHT_LEFT -> -45f; Dir.RIGHT, Dir.SHARP_RIGHT -> 90f; Dir.SLIGHT_RIGHT -> 45f
            Dir.ROUNDABOUT -> 50f; Dir.UTURN -> 180f; else -> 0f
        }
        val l = loc; val p = turnPoint; val hd = heading()
        var bear = 0f
        if (l != null && p != null && hd != null) {
            val r = FloatArray(2); Location.distanceBetween(l.latitude, l.longitude, p.first, p.second, r)
            bear = r[1] - hd
            while (bear > 180) bear -= 360; while (bear < -180) bear += 360
        }
        val k = (1 - (navMeters - 20) / 110).coerceIn(0.0, 1.0).toFloat()
        return bear * (1 - k) + exitA * k
    }

    private fun project(lat: Double, lon: Double, bearingDeg: Double, m: Double): Pair<Double, Double> {
        val r = 6371000.0; val b = Math.toRadians(bearingDeg); val d = m / r
        val la = Math.toRadians(lat); val lo = Math.toRadians(lon)
        val la2 = Math.asin(sin(la) * cos(d) + cos(la) * sin(d) * cos(b))
        val lo2 = lo + atan2(sin(b) * sin(d) * cos(la), cos(d) - sin(la) * sin(la2))
        return Math.toDegrees(la2) to Math.toDegrees(lo2)
    }

    // ================================================================= indicators

    private var leftSeen = 0L
    private var rightSeen = 0L
    private var indOnS = 0
    private var gttS = 0
    private var blinkPhase = false

    /**
     * The blink bit toggles with the lamp, but status frames come only every ~0.3–2 s and often land on the "off" half of
     * the blink several times in a row (ride 2026-11-27: lit samples 2–3 s apart, up to ~3.3 s; with a 1.5 s window it kept dropping out, one lone tick each time).
     * So "on" = seen lit in the last [IND_HOLD_MS]; the tick-tock keeps its own steady rhythm in between. Both = hazard.
     */
    private fun onIndicators(s: BikeState) {
        val now = SystemClock.elapsedRealtime()
        if (s.leftInd) leftSeen = now
        if (s.rightInd) rightSeen = now
        val wasOn = indSide() != null
        Bike.hazard.value = now - leftSeen < IND_HOLD_MS && now - rightSeen < IND_HOLD_MS && abs(leftSeen - rightSeen) < 1500
        val side = indSide()
        if (side != Bike.indicator.value) { Bike.indicator.value = side; Bike.log("[ind] ${side ?: "off"}") }
        if (!wasOn && side != null) { h.removeCallbacks(blinker); h.post(blinker) }
    }

    private val IND_HOLD_MS = 3500L

    private fun indSide(): String? {
        val now = SystemClock.elapsedRealtime()
        val l = now - leftSeen < IND_HOLD_MS; val r = now - rightSeen < IND_HOLD_MS
        return when { Bike.hazard.value && l && r -> "both"; l && r -> if (leftSeen > rightSeen) "left" else "right"; l -> "left"; r -> "right"; else -> null }
    }

    /** Tick-tock in the ear on that side (both for hazard) while an indicator blinks. */
    private val blinker = object : Runnable {
        override fun run() {
            val side = indSide()
            if (side != Bike.indicator.value) { Bike.indicator.value = side; Bike.log("[ind] ${side ?: "off"}") }
            if (side == null) return
            if (ready && prefs.sndInd) sounds.play((if (blinkPhase) "ind_tick_" else "ind_tock_") + side)
            blinkPhase = !blinkPhase
            h.postDelayed(this, 360)
        }
    }

    // ================================================================= engine warm-up (cold start)

    private fun onEngineStart(s: BikeState) {
        val t = s.engineTempC
        if (t == null) pendingWarmCheck = true else startWarmup(t)
    }

    private var pendingWarmCheck = false

    /** A cold start: the engine is below "warm" minus a margin. Shows a warm-up bar and says so on the bike for a moment. */
    private fun startWarmup(t: Int) {
        pendingWarmCheck = false
        val warm = prefs.warmC
        if (t >= warm - 5) { Bike.warmup.value = null; return }
        Bike.warmup.value = WarmUp(t, t, warm, System.currentTimeMillis())
        Bike.log("[warm-up] cold start at $t °C, warm at $warm °C")
        if (ready && callState == TelephonyManager.EXTRA_STATE_IDLE) { send("cold", Protocol.text("COLD ENGINE")); clearLater(6000) }
    }

    private fun onTemp(s: BikeState) {
        val t = s.engineTempC ?: return
        if (pendingWarmCheck && s.engineOn) { startWarmup(t); return }
        val w = Bike.warmup.value ?: return
        if (!s.engineOn) { Bike.warmup.value = null; return }
        if (t >= w.warmC) {
            Bike.warmup.value = null
            val min = (System.currentTimeMillis() - w.sinceMs) / 60_000
            Bike.log("[warm-up] engine warm ($t °C) after $min min")
            if (prefs.sndWarm) sounds.play("engine_warm")
        } else if (t != w.nowC) Bike.warmup.value = w.copy(nowC = t)
    }

    // ================================================================= fuel stop

    private var barsAtStop = -1
    private var stoppedS = 0
    private var askBars0 = -1

    private fun onFuelBars(old: Int, bars: Int) {
        fuel.seedFromBars(bars)
        // filled while the bike was off (ignition off at the pump): the first reading is higher than when it went off
        if (old == 0 && Bike.state.value.frames <= 3 && prefs.lastBars in 0 until bars - 1 && Bike.ask.value == null) {
            askBars0 = prefs.lastBars
            Bike.ask.value = FuelAsk(station = nearStation() ?: "")
            Bike.log("[fuel] gauge went up while parked (${prefs.lastBars} → $bars bars): asking")
        }
    }

    private fun nearStation(): String? {
        val l = loc ?: return null
        return prefs.stations.split(";").mapNotNull { e -> e.split(",").takeIf { it.size == 2 }?.let { it[0].toDoubleOrNull() to it[1].toDoubleOrNull() } }
            .firstOrNull { (a, b) -> a != null && b != null && FloatArray(1).also { r -> Location.distanceBetween(l.latitude, l.longitude, a, b, r) }[0] < 40 }
            ?.let { "a fuel station you've used" }
    }

    private fun learnStation() {
        val l = loc ?: return
        val list = prefs.stations.split(";").filter { it.isNotBlank() }
        if (nearStation() != null) return
        prefs.stations = (list + "%.5f,%.5f".format(Locale.US, l.latitude, l.longitude)).takeLast(40).joinToString(";")
    }

    /** Once a second: parked at a known station with the engine off, or a stop where the gauge then went up. */
    private fun fuelStopTick(s: BikeState) {
        if (s.speed == 0) stoppedS++ else stoppedS = 0
        if (stoppedS == 1) barsAtStop = s.fuelBars
        val a = Bike.ask.value
        if (a == null && s.speed == 0 && (!s.engineOn || stoppedS >= 30) && nearStation() != null) {
            askBars0 = s.fuelBars
            Bike.ask.value = FuelAsk(station = nearStation()!!)
            Bike.log("[fuel] stopped at a known fuel station → \"Filled up?\" card (no notification)")
        }
        // rode off without answering: the bike's fuel gauge decides
        val cur = Bike.ask.value
        if (cur != null && !cur.late && s.speed > 5) {
            val b0 = if (askBars0 >= 0) askBars0 else barsAtStop
            if (s.fuelBars - b0 >= 1) {
                val guess = fuel.gaugeFill(b0, s.fuelBars)
                Bike.ask.value = cur.copy(late = true, guess = guess, stage = "ask")
                Bike.log("[fuel] no answer → going by the fuel gauge ($b0 → ${s.fuelBars} bars): $guess")
            } else { Bike.ask.value = null; Bike.log("[fuel] rode off, gauge didn't go up → nothing filled") }
        }
        // a stop where the gauge went up (no known station): ask
        if (cur == null && barsAtStop >= 0 && s.speed == 0 && s.fuelBars - barsAtStop >= 2) {
            askBars0 = barsAtStop; Bike.ask.value = FuelAsk(); barsAtStop = s.fuelBars
            Bike.log("[fuel] gauge went up after a stop → asking")
        }
        // a late question waits ~3 h, then goes away
        if (cur != null && cur.late && System.currentTimeMillis() - cur.at > 3 * 3600_000L) Bike.ask.value = null
    }

    /** The rider's answer on the "Filled up?" card. */
    fun answerFuel(kind: String, value: Double? = null): String {
        val msg = when (kind) {
            "full" -> fuel.fullTank(value)
            "litres" -> fuel.added(value ?: 0.0)
            "rs" -> fuel.added((value ?: 0.0) / prefs.fuelPrice)
            "keep" -> { fuel.keepGuess(); "Kept the gauge guess" }
            "no" -> { fuel.undoGuess(); "Didn't fill: nothing changes" }
            else -> ""
        }
        if (kind == "full" || kind == "litres" || kind == "rs") learnStation()
        Bike.ask.value = null
        Bike.log("[fuel] $msg")
        publishFuel(Bike.state.value, Bike.conn.value.phase == Phase.LIVE)
        return msg
    }

    // ================================================================= speed alert + service reminder

    private var overSince = 0L
    private var lastSpeedAlert = 0L
    private var lastServiceCheck = 0L
    private var serviceDueMsg: String? = null

    private fun checkSpeed(kmh: Int) {
        if (!prefs.speedAlert) return
        val now = System.currentTimeMillis()
        if (kmh <= prefs.speedLimit) { overSince = 0; return }
        if (overSince == 0L) overSince = now
        if (now - overSince < 3000 || now - lastSpeedAlert < 30_000) return
        lastSpeedAlert = now
        Bike.log("[speed] $kmh km/h over the ${prefs.speedLimit} limit")
        // nothing pops up on screen: two pings (+ "Slow down" if Voice allows) and the bike's own overspeed warning
        if (prefs.sndSpeed) sounds.cue("speed", "Slow down")
        if (prefs.speedBeep) getSystemService(android.os.VibratorManager::class.java).defaultVibrator
            .vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
    }

    private fun checkService(odo: Double) {
        val now = System.currentTimeMillis()
        if (odo <= 0 || now - lastServiceCheck < 60_000) return
        lastServiceCheck = now
        prefs.lastOdo = odo
        val due = prefs.nextServiceAt(odo)
        val left = (due - odo).toInt()
        Bike.serviceLeftKm.value = left
        if (left > prefs.serviceWarnKm || prefs.serviceWarnedFor == due) return
        prefs.serviceWarnedFor = due
        serviceDueMsg = if (left <= 0) "Service overdue by ${-left} km (due at $due km)" else "Service due in $left km (at $due km)"
        Bike.log("[service] $serviceDueMsg (notification after the ride)")
    }

    /** The only notification the app posts: service due, after a ride (never while moving). Plus a short text on the bike when parked. */
    private fun postServiceDue() {
        val msg = serviceDueMsg ?: return
        serviceDueMsg = null
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("service", "Service reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(9, Notification.Builder(this, "service").setSmallIcon(R.drawable.ic_mark)
            .setContentTitle("Ronin service reminder").setContentText(msg).setAutoCancel(true).build())
    }

    private var serviceShownParked = false
    private fun serviceParked(s: BikeState) {
        val left = Bike.serviceLeftKm.value ?: return
        if (serviceShownParked || left > prefs.serviceWarnKm || s.speed > 0 || !ready) return
        serviceShownParked = true
        if (prefs.sndWarn) sounds.cue("service", "Service due")
        send("service", Protocol.text(if (left <= 0) "SERVICE DUE" else "SERVICE IN ${left}KM")); clearLater(10_000)
    }

    // ================================================================= bike display brightness

    private var clSent = 0
    private var clWant = 0
    private var clSteady = 0
    private var clLastWrite = 0L

    /** If the bike reports a level we didn't send, the rider changed it (Mode → Illumination): hands off for this ride. */
    private fun onClusterLevel(level: Int) {
        if (level !in 2..10) return
        val prev = Bike.clusterLevel.value
        Bike.clusterLevel.value = level
        if (prev != 0 && level != prev && level != clSent && !Bike.clusterManual.value) {
            Bike.clusterManual.value = true
            Bike.log("[bright] the bike now reports $level, which we didn't send → you changed it; auto stops for this ride")
        }
    }

    private fun brightnessTick() {
        val mounted = sensors.mounted && sensors.lux >= 0
        val trust = ready && prefs.clusterBright && mounted && !Bike.clusterManual.value && Bike.clusterLevel.value != 0
        if (!trust) { clSteady = 0; return }
        val want = PhoneSensors.levelFor(sensors.lux)
        val cur = Bike.clusterLevel.value
        if (want == cur) { clSteady = 0; return }
        clSteady = if (want == clWant) clSteady + 1 else 1
        clWant = want
        // brighter quickly (glare matters now), dimmer slowly (a shadow or a bridge shouldn't flicker the display)
        if (clSteady >= (if (want > cur) 2 else 6)) {
            clSteady = 0; writeBrightness(want, "light ≈ ${sensors.lux.toInt()} lux")
        }
    }

    /** The rider's own choice from the dashboard: Auto on/off (kept), or a level 2–10 (turns Auto off). */
    fun riderAuto(on: Boolean) = h.post {
        prefs.clusterBright = on
        Bike.clusterManual.value = !on
        clSteady = 0
        Bike.log("[bright] auto ${if (on) "on" else "off"} (you)")
    }

    fun riderLevel(level: Int) = h.post {
        prefs.clusterBright = false; Bike.clusterManual.value = true
        if (ready) writeBrightness(level.coerceIn(2, 10), "set by you")
    }

    private fun writeBrightness(level: Int, why: String) {
        clSent = level; clLastWrite = SystemClock.elapsedRealtime()
        send("brightness $level", Protocol.brightness(level))
        Bike.log("[bright] ${Bike.clusterLevel.value} → $level ($why)")
    }

    /**
     * The one thing assistants may do: low | high | up | down | 2–10. Returns what the assistant should say.
     * Counts as the rider's choice, so auto brightness pauses for the rest of this ride.
     */
    fun setBrightness(cmd: String): Pair<Boolean, String> {
        if (!prefs.agentBright) return false to "Ronin Dash doesn't take requests from assistants (turned off in Settings)."
        val cur = Bike.clusterLevel.value.takeIf { it in 2..10 } ?: 6
        val to = when (cmd.lowercase(Locale.ROOT).trim()) {
            "low" -> 2; "high" -> 10; "up" -> min(10, cur + 2); "down" -> max(2, cur - 2)
            else -> cmd.trim().toIntOrNull()?.takeIf { it in 2..10 } ?: return false to "Ronin Dash can only make the bike display brighter or dimmer."
        }
        if (!ready) return false to "The bike isn't connected. Turn the ignition on and try again."
        if (SystemClock.elapsedRealtime() - clLastWrite < 2000) return false to "One moment, the bike display is still changing."
        if (to == Bike.clusterLevel.value) return true to "The bike display is already at ${to * 10} %."
        Bike.clusterManual.value = true
        writeBrightness(to, "asked by an assistant")
        return true to "Bike display brightness ${to * 10} %."
    }

    // ================================================================= dashboard opens itself on ignition

    private fun maybeOpenDashboard() {
        if (!prefs.autoOpen || !prefs.setupDone) return
        val m = sensors.mounted
        Bike.mounted.value = m
        if (!m) { Bike.log("[open] phone not on the mount (pocket or covered) → dashboard stays closed"); return }
        try {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.BY_BIKE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            Bike.log("[open] phone on the mount → dashboard opened by itself")
        } catch (e: Exception) { Bike.log("[open] not allowed: ${e.message}") }
    }

    // ================================================================= the 1-second loop: GPS, fuel, trips, screens, sensors

    private val locListener = LocationListener { l -> loc = l; crash.lastLoc = l; Bike.hasGps.value = true }

    private fun gps(on: Boolean, everyMs: Long = 5000) {
        if (on == gpsOn && everyMs == gpsEvery) return
        val lm = getSystemService(LocationManager::class.java)
        lm.removeUpdates(locListener)
        if (on) {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) { gpsOn = false; return }
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, everyMs, 0f, locListener, Looper.getMainLooper())
                gpsOn = true; gpsEvery = everyMs
                if (loc == null) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc = it; crash.lastLoc = it }
            } catch (e: Exception) { Bike.log("[gps] ${e.message}") }
        } else { gpsOn = false; gpsEvery = 0; Bike.hasGps.value = false }
    }

    private var lastMovingMs = 0L
    private var parkS = 0
    private var fastS = 0
    private var slowS = 0
    private var downS = 0
    private var lastMovedFastMs = 0L
    private var lowWarned = false

    private val secondTick = object : Runnable {
        override fun run() {
            h.postDelayed(this, 1000)
            val live = Bike.conn.value.phase == Phase.LIVE && ready
            val s = Bike.state.value
            val now = System.currentTimeMillis()
            val moving = live && s.speed > 3 && "speed" !in s.unknown
            if (moving) lastMovingMs = now
            // GPS only while moving and connected, a fix every ~5 s (15 s when the phone is hot), off after 60 s stopped
            val hot = getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
            gps(live && now - lastMovingMs < 60_000, if (hot) 15_000 else 5000)
            val l = loc
            val fresh = l != null && now - l.time < 6500 && l.hasAccuracy() && l.accuracy < 25
            Bike.gpsLost.value = moving && !fresh
            val gpsKmh = if (fresh && l!!.hasSpeed()) l.speed * 3.6 else null
            val rpm = if (live && "rpm" !in s.unknown) s.rpm else 0
            val before = fuel.tripL
            val km = fuel.step(1.0, rpm, s.throttlePct, if (live) s.speed.toDouble() else 0.0, gpsKmh, live && s.engineOn, prefs.tripRecord,
                gear = if (live) s.gear else -1, engineC = if (live) s.engineTempC else null,
                bars = if (live && s.frames > 2) s.fuelBars else -1, odoKm = if (live) s.odoKm else 0.0,
                clutchIn = live && (s.clutch || s.neutral))
            val litres = fuel.tripL - before
            crash.setRiding(live && (s.engineOn || s.speed > 0))
            // GTT on screen: 2 s of agreeing readings to come on, 2 s to go off (throttle arrives only every ~1.3 s)
            gttS = if (live && s.gtt) max(1, gttS + 1) else min(-1, gttS - 1)
            val gttNow = if (gttS >= 2) true else if (gttS <= -2) false else Bike.gtt.value
            if (gttNow != Bike.gtt.value) { Bike.gtt.value = gttNow; Bike.log("[gtt] ${if (gttNow) "on" else "off"} (gear ${s.gear}, ${s.speed} km/h, rpm ${s.rpm})") }
            if (prefs.tripRecord) trips.sample(s, loc, live, km, if (litres > 0) litres else 0.0)
            Bike.liveTripKm.value = if (trips.active) trips.liveKm else 0.0
            Bike.liveTripKmpl.value = fuel.live
            Bike.tripStoppedMin.value = trips.stoppedForMin
            publishFuel(s, live)
            if (live) {
                fuelStopTick(s)
                screenMode(s, live)
                sensorsTick(s, moving, gpsKmh)
                brightnessTick()
                if (!holding()) releaseHeld()
                if (s.speed == 0) serviceParked(s)
                // indicator left on while riding for 30 s: one nudge
                indOnS = if (indSide() != null && !Bike.hazard.value && s.speed > 20) indOnS + 1 else 0
                if (indOnS == 30 && prefs.sndInd) sounds.cue("ind_forgot", "Indicator")
                // countdown between Maps updates, from our own speed
                if (navMeters > 0 && moving) { navMeters = max(0.0, navMeters - (gpsKmh ?: (s.speed / prefs.speedoK)) / 3.6); updateTurn(sendNow = false) }
                if (battAtStart > 0) Bike.rideBatteryUsed.value = (battAtStart - batteryPct()).coerceAtLeast(0)
                Bike.guardFailing.value = guard.failing()
            } else Bike.mode.value = RideMode.PARK
            if (tick % 30 == 0) fuel.save()
        }
    }

    private fun publishFuel(s: BikeState, live: Boolean) {
        val rng = fuel.range
        if (live && fuel.tankKnown && rng < 40 && !lowWarned) { lowWarned = true; if (prefs.sndWarn) sounds.cue("fuel_low", "Fuel low"); Bike.log("[fuel] range ${rng.toInt()} km") }
        if (rng > 55) lowWarned = false
        val moving = live && s.speed > 3
        Bike.fuel.value = FuelView(
            tankL = max(0.0, fuel.tank), tankKnown = fuel.tankKnown, rangeKm = rng, recentKmpl = fuel.recent,
            liveKmpl = fuel.live, lph = fuel.lphNow, moving = moving, idling = live && !moving && s.engineOn,
            tripKm = fuel.tripKm, tripL = fuel.tripL, tripMovingS = fuel.tripMovingS, low = fuel.tankKnown && rng < 40,
        )
    }

    /**
     * The riding screen only while actually riding: engine on, moving, side stand up, clutch out (a clutch pulled at very
     * low speed = stopping). Anything else for 2 s → the normal dashboard, so it's there at a signal without putting the stand
     * down (owner, 2026-11-27). A clutch pull for a gear change while moving doesn't count.
     * Fast = at or above the fast speed for 3 s, back below it −15 for 4 s.
     */
    private fun screenMode(s: BikeState, live: Boolean) {
        val parkedNow = !live || !s.engineOn || s.sideStand || s.speed == 0 || (s.clutch && s.speed < 8)
        parkS = if (parkedNow) parkS + 1 else 0
        fastS = if (live && s.speed >= prefs.fastKmh) fastS + 1 else 0
        slowS = if (s.speed < prefs.fastKmh - 15) slowS + 1 else 0
        var m = Bike.mode.value
        if (parkS >= 2) m = RideMode.PARK else if (m == RideMode.PARK && !parkedNow) m = RideMode.RIDE
        if (m == RideMode.RIDE && fastS >= 3) m = RideMode.FAST
        if (m == RideMode.FAST && slowS >= 4) m = RideMode.RIDE
        if (m != Bike.mode.value) { Bike.mode.value = m; Bike.log("[screen] $m") }
    }

    /** Lean angle, compass and bike-down: only while riding with the phone on the mount. */
    private fun sensorsTick(s: BikeState, moving: Boolean, gpsKmh: Double?) {
        val mounted = sensors.mounted
        Bike.mounted.value = mounted
        sensors.riding(mounted && (moving || s.engineOn))
        if (!mounted) return
        val l = loc
        if (moving && s.speed > 25 && l != null && l.hasBearing()) sensors.onRidingStraight(l.bearing)
        if (s.speed > 15) {
            lastMovedFastMs = System.currentTimeMillis()
            val lean = sensors.lean.roundToInt()
            val lv = Bike.lean.value
            Bike.lean.value = Lean(lean, if (lean < 0) max(lv.maxLeft, -lean) else lv.maxLeft, if (lean > 0) max(lv.maxRight, lean) else lv.maxRight)
        } else if (Bike.lean.value.now != 0) Bike.lean.value = Bike.lean.value.copy(now = 0)
        // Bike down: lying past ~60° for 3 s right after moving, then stopped → the crash alert (a dropped phone doesn't stay at 60°+).
        val tipped = abs(sensors.lean) > 60 && System.currentTimeMillis() - lastMovedFastMs < 15_000 && s.speed < 5
        downS = if (tipped) downS + 1 else 0
        if (downS == 3 && prefs.crashDetect) crash.trigger("Bike down: lying on its side after moving (${abs(sensors.lean).roundToInt()}°)", "Bike down. Hold the I button if you're ok")
    }

    /** Rider taps "End ride" in the Trips tab. */
    fun endRide() = h.post { trips.finish(Bike.state.value) }

    /** Manual SOS from the app (same countdown as a detected crash). */
    fun manualSos() = h.post { crash.trigger("SOS button pressed by the rider") }

    // ================================================================= CSV log (adb pull /sdcard/Android/data/dev.ronindash/files/logs/)

    private fun openCsv() {
        val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
        val file = File(dir, "ride_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv")
        csvWriter = FileWriter(file).apply { write("time,dir,raw,decoded,note\n") }
        Bike.logFile.value = file.absolutePath
    }

    @Synchronized
    fun csv(dir: String, raw: String, dec: String, note: String) {
        try { csvWriter?.apply { write("${ts.format(Date())},$dir,$raw,$dec,\"${note.replace("\"", "'")}\"\n"); flush() } } catch (_: Exception) {}
    }
}
