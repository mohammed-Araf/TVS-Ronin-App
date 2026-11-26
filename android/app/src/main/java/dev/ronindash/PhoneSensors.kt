package dev.ronindash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Phone sensors, only where they earn their battery (mock FIXES 25–26, 29):
 * - light + proximity + gravity (cheap, already on for the phone's own auto-brightness): is the phone on the mount, facing
 *   the sky, or in a pocket; and how bright it is outside (for the bike display brightness).
 * - while riding with the phone on the mount: lean angle (gravity roll, zeroed while riding straight) and compass heading
 *   (for the turn compass below ~15 km/h, where GPS heading is noise).
 * Everything batched at ~10 Hz; nothing registered while the bike is off.
 */
class PhoneSensors(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var basic = false
    private var riding = false

    /** Smoothed ambient light (lux); -1 = unknown. */
    @Volatile var lux = -1f; private set
    @Volatile var proxNear = false; private set
    private val g = FloatArray(3)
    /** Screen facing the sky: the phone stands within ~70° of vertical, or lies face up (not face down). */
    val facingSky: Boolean get() { val n = norm(); return n > 0 && (g[2] / n) > -0.35f && tiltFromVertical() < 70 || (n > 0 && g[2] / n > 0.9f) }
    /** The pocket pattern: proximity covered, dark, and upright. */
    val inPocket: Boolean get() = proxNear && lux in 0f..5f
    val mounted: Boolean get() = !proxNear && !inPocket && facingSky

    /** Roll in degrees (left negative) and its slowly learned "riding straight" zero. */
    @Volatile var roll = 0f; private set
    private var rollZero = Float.NaN
    val lean: Float get() = if (rollZero.isNaN()) 0f else roll - rollZero
    /** Compass heading (degrees, 0 = north), corrected against GPS heading while riding straight. */
    @Volatile var heading = Float.NaN; private set
    private var headingFix = 0f

    private fun norm() = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
    private fun tiltFromVertical(): Double {
        val n = norm(); if (n == 0f) return 90.0
        return Math.toDegrees(kotlin.math.acos((g[1] / n).toDouble().coerceIn(-1.0, 1.0)))
    }

    /** Light, proximity and gravity: while connected. */
    fun basic(on: Boolean) {
        if (on == basic) return
        basic = on
        if (on) listOf(light, prox, grav).forEach { s -> s?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 200_000) } }
        else { sm.unregisterListener(this); riding = false; lux = -1f; rollZero = Float.NaN }
    }

    /** Rotation vector for heading + fast gravity for lean: only while riding with the phone on the mount. */
    fun riding(on: Boolean) {
        if (on == riding || !basic) return
        riding = on
        if (on) {
            rot?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, 100_000) }
            grav?.let { sm.unregisterListener(this, it); sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, 100_000) }
        } else {
            rot?.let { sm.unregisterListener(this, it) }
            grav?.let { sm.unregisterListener(this, it); sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 200_000) }
        }
    }

    /** Called once a second while riding: learns the straight-up roll and corrects the compass from GPS heading. */
    fun onRidingStraight(gpsBearing: Float?) {
        rollZero = if (rollZero.isNaN()) roll else rollZero + (roll - rollZero) * .02f
        if (gpsBearing != null && !heading.isNaN()) {
            var d = gpsBearing - heading
            while (d > 180) d -= 360; while (d < -180) d += 360
            headingFix += d * .05f
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LIGHT -> lux = if (lux < 0) e.values[0] else lux * .7f + e.values[0] * .3f
            Sensor.TYPE_PROXIMITY -> proxNear = e.values[0] < (prox?.maximumRange ?: 5f).coerceAtMost(3f)
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) g[i] = g[i] * .5f + e.values[i] * .5f
                // roll around the phone's long axis as it stands in a mount (portrait): left lean = negative
                roll = Math.toDegrees(atan2(g[0].toDouble(), sqrt((g[1] * g[1] + g[2] * g[2]).toDouble()))).toFloat()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = FloatArray(9); val o = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(r, e.values)
                // phone standing in a mount: remap so the heading is where the back of the phone points (the road)
                val rr = FloatArray(9)
                SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, rr)
                SensorManager.getOrientation(rr, o)
                heading = ((Math.toDegrees(o[0].toDouble()).toFloat() + headingFix) % 360 + 360) % 360
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    companion object {
        /** Lux → bike display level (2–10): night · tunnel/dusk · evening · daylight shade · sun. */
        fun levelFor(lux: Float) = when { lux < 15 -> 2; lux < 150 -> 4; lux < 1500 -> 6; lux < 12000 -> 8; else -> 10 }
        fun absAngle(a: Float) = abs(a)
    }
}
