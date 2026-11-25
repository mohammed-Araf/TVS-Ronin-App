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
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the bike connected in the background:
 * - one GATT write at a time (queue), auto-reconnect when the bike comes back
 * - mobile status every 2 s (clock, real missed calls / unread messages, no stuck voice-assist state)
 * - I button acted on once per press (rising edge), never repeated while the bike holds the code
 * - caller name, SMS/WhatsApp sender and Google Maps turns pushed to the cluster
 */
@SuppressLint("MissingPermission")
class BikeService : Service() {

    companion object {
        private const val CH = "bike"
        private const val NOTIF_ID = 1
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, BikeService::class.java))
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
    private var lastCode = 0
    private var callState: String = TelephonyManager.EXTRA_STATE_IDLE
    private var announced: String? = null
    private var vaUntil = 0L
    private var lastAssistAt = 0L
    private var tick = 0
    private var navActive = false
    private var lastNavKey = ""
    private var lastNavSent = 0L
    private var csvWriter: FileWriter? = null
    private val lastByType = HashMap<Int, ByteArray>()
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    lateinit var crash: CrashGuard; private set
    private lateinit var trips: TripRecorder
    private var loc: Location? = null
    private var gpsOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Bike.service = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Bike connection", NotificationManager.IMPORTANCE_LOW))
        crash = CrashGuard(this, h) { l, f -> send(l, f) }
        trips = TripRecorder(this)
        try {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (has(Manifest.permission.ACCESS_FINE_LOCATION)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIF_ID, notif("Starting…"), type)
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
        h.postDelayed(tripTick, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        stopping = true
        h.removeCallbacksAndMessages(null)
        if (::trips.isInitialized) trips.finish(Bike.state.value)
        if (::crash.isInitialized) crash.stop()
        gps(false)
        try { unregisterReceiver(phoneReceiver) } catch (_: Exception) {}
        closeGatt()
        csvWriter?.close(); csvWriter = null
        Bike.service = null
        Bike.conn.value = Conn(Phase.OFF, "Stopped")
        super.onDestroy()
    }

    // ================================================================= connection

    private fun setConn(p: Phase, d: String) {
        if (Bike.conn.value == Conn(p, d)) return
        Bike.conn.value = Conn(p, d)
        Bike.log("[conn] $d")
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif(d))
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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
    }

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

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = h.post {
            if (g != gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setConn(Phase.CONNECTING, "Connected, reading bike…")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Bike.log("[conn] link lost, status=$status")
                closeGatt()
                if (!stopping) {
                    // autoConnect waits quietly until the bike is in range again.
                    setConn(Phase.WAITING, "Waiting for the bike (ignition on?)")
                    h.postDelayed({ connect(auto = true) }, 1000)
                }
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
            ready = true; retry = 0
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

    private fun onReady() {
        val name = Protocol.clean(prefs.riderName.replace(" ", ""))
        if (name.isNotEmpty()) send("name", Protocol.riderName(name))
        sendStatus()
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

    private fun sendStatus() {
        val bm = getSystemService(BatteryManager::class.java)
        val batt = ((bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + 12) / 25).coerceIn(0, 4)
        val va = if (System.currentTimeMillis() < vaUntil) 32 else 0
        val os = if (prefs.speedAlert && prefs.clusterOverspeed) prefs.speedLimit else 120
        send("status", Protocol.status(Bike.missedCalls.value, Bike.unreadMessages.value, va, 4, batt, os))
    }

    // ================================================================= frames from the bike

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
        Bike.state.value = Bike.state.value.update(u)
        val prev = lastByType[u[1]]
        if (prev == null || !prev.contentEquals(f)) {
            lastByType[u[1]] = f
            Bike.log("← ${Protocol.describe(u)}")
            if (BuildConfig.DEBUG) android.util.Log.d("RoninRaw", Protocol.hex(f))
        }
        if (u[1] == 0x10) { onButtons(u[11]); checkSpeed(Bike.state.value.speed); checkService(Bike.state.value.odoKm) }
        if (u[1] == 0x11 || u[1] == 0x10) crash.onBike(Bike.state.value, true)
    }

    /** Act once when a button code first appears. The bike repeats it for ~5 s; we ignore the repeats. */
    private fun onButtons(b: Int) {
        val code = b and 0x07
        if (code == lastCode) return
        lastCode = code
        if (code == 0) return
        if (Bike.sos.value != null) { crash.cancel("bike button"); return }
        Bike.log("[button] code=$code (byte 0x%02X) during call state $callState".format(b))
        when (code) {
            1 -> if (callState == TelephonyManager.EXTRA_STATE_RINGING) acceptCall()
            2 -> when (callState) {
                TelephonyManager.EXTRA_STATE_RINGING -> endCall("reject")
                TelephonyManager.EXTRA_STATE_OFFHOOK -> endCall("hang up")
                else -> clearDisplay()
            }
            3 -> {} // mode button: the bike handles riding modes itself
            // One assistant per press: ignore repeats for 10 s so it never keeps popping up.
            4 -> if (callState == TelephonyManager.EXTRA_STATE_IDLE && System.currentTimeMillis() - lastAssistAt > 10_000) launchAssistant()
        }
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

    // ================================================================= calls

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION") val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            h.post { onCallState(st, num) }
        }
    }

    private fun onCallState(st: String, num: String?) {
        val prev = callState
        callState = st
        if (prev != st) Bike.log("[call] $prev → $st")
        when (st) {
            TelephonyManager.EXTRA_STATE_RINGING -> if (num != null && num != announced && prefs.calls) {
                announced = num
                val name = Protocol.clean(lookupName(num) ?: num.replace(Regex("^\\+91"), "").filter { it.isDigit() || it == '+' })
                // The cluster can miss one frame; 3 copies is plenty (the official app sends 11).
                for (k in 0 until 3) h.postDelayed({
                    if (callState == TelephonyManager.EXTRA_STATE_RINGING) send("caller", Protocol.caller(name))
                }, k * 700L)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (prev != TelephonyManager.EXTRA_STATE_IDLE) clearLater(1500) // close the caller pop-up
                announced = null
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
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.NEW}=1", arrayOf(CallLog.Calls.MISSED_TYPE.toString()), null,
            )?.use { Bike.missedCalls.value = it.count }
        } catch (_: Exception) {}
    }

    // ================================================================= messages & navigation (from NotifListener)

    fun onMessage(sender: String) {
        val s = Protocol.clean(sender, 16)
        if (s.isNotEmpty()) { send("sms", Protocol.sms(s)); clearLater() }
        sendStatus()
    }

    fun onNav(n: NavInfo?) = h.post {
        if (!prefs.navOn) return@post
        if (n == null) {
            if (navActive) send("nav-end", Protocol.nav(0, 0, 0, 7, 7))
            navActive = false; lastNavKey = ""
            return@post
        }
        val next = if (n.nextM >= 100) (n.nextM + 25) / 50 * 50 else n.nextM
        val key = "$next|${n.dir}|${n.road}|${n.minutesLeft}"
        val now = System.currentTimeMillis()
        if (key == lastNavKey && now - lastNavSent < 5000) return@post
        lastNavKey = key; lastNavSent = now; navActive = true
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

    // ================================================================= speed alert + service reminder

    private var overSince = 0L
    private var lastSpeedAlert = 0L
    private var lastServiceCheck = 0L
    private val tone by lazy { android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 90) }

    private fun checkSpeed(kmh: Int) {
        if (!prefs.speedAlert) return
        val now = System.currentTimeMillis()
        if (kmh <= prefs.speedLimit) { overSince = 0; return }
        if (overSince == 0L) overSince = now
        if (now - overSince < 3000 || now - lastSpeedAlert < 30_000) return
        lastSpeedAlert = now
        Bike.log("[speed] $kmh km/h over the ${prefs.speedLimit} limit")
        if (prefs.speedBeep) try { tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 600) } catch (_: Exception) {}
        getSystemService(android.os.VibratorManager::class.java).defaultVibrator
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
        val msg = if (left <= 0) "Service overdue by ${-left} km (due at $due km)" else "Service due in $left km (at $due km)"
        Bike.log("[service] $msg")
        send("service", Protocol.text(if (left <= 0) "SERVICE DUE" else "SERVICE IN ${left}KM")); clearLater(10_000)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("service", "Service reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(9, Notification.Builder(this, "service").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Ronin service reminder").setContentText(msg).setAutoCancel(true).build())
    }

    // ================================================================= trips, GPS, crash

    private val locListener = LocationListener { l -> loc = l; crash.lastLoc = l; Bike.hasGps.value = true }

    private fun gps(on: Boolean) {
        if (on == gpsOn) return
        val lm = getSystemService(LocationManager::class.java)
        if (on) {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
                gpsOn = true
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc = it; crash.lastLoc = it }
            } catch (e: Exception) { Bike.log("[gps] ${e.message}") }
        } else {
            lm.removeUpdates(locListener); gpsOn = false; Bike.hasGps.value = false
        }
    }

    private val tripTick = object : Runnable {
        override fun run() {
            val live = Bike.conn.value.phase == Phase.LIVE
            val s = Bike.state.value
            // GPS + crash sensors only while the bike is on (saves battery when parked).
            gps(live || trips.recentlyMoving)
            crash.setRiding(live && (s.engineOn || s.speed > 0))
            if (prefs.tripRecord) trips.sample(s, loc, live)
            Bike.liveTripKm.value = if (trips.active) trips.liveKm else 0.0
            Bike.liveTripKmpl.value = if (trips.active) trips.liveKmpl else 0.0
            Bike.tripStoppedMin.value = trips.stoppedForMin
            h.postDelayed(this, 1000)
        }
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
