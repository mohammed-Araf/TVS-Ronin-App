package dev.ronindash

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import kotlin.math.abs

/**
 * Google Maps' navigation notification no longer says "turn left" (ride 2026-11-27: title = "90 m", text = the road name
 * only); the direction is only in its arrow picture (the large icon). This reads the arrow:
 *
 *  - draws the icon at 32×32 and finds the arrow pixels (they differ from the corner colour: white arrow on transparent or
 *    on Maps green);
 *  - the stem starts at the bottom; where the top of the arrow sits relative to the stem says the side and how far:
 *    far left = left, a little left = slight left, beyond the stem's side and low = sharp, centred = straight.
 *  - a known picture (same 16×16 shape) is looked up first: [KNOWN] is filled in from saved icons as they are checked.
 *
 * Every new picture is saved once to files/notes/maps_icons/<hash>.png with what we guessed, so the guesses can be checked
 * against what Maps showed and fixed. The rider never sees any of this.
 */
object MapsIcon {
    /** Shape hash → direction, confirmed from saved icons (empty until checked). */
    private val KNOWN = mapOf<String, Dir>()

    private val saved = HashSet<String>()

    data class Read(val dir: Dir, val hash: String, val dx: Float, val note: String)

    fun read(ctx: Context, n: Notification, title: String, text: String): Read? {
        val bmp = bitmap(ctx, n) ?: return null
        val r = classify(bmp) ?: return null
        save(ctx, bmp, r, title, text)
        return r
    }

    private fun bitmap(ctx: Context, n: Notification): Bitmap? = try {
        val d = n.getLargeIcon()?.loadDrawable(ctx)
            ?: (n.extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)?.let { android.graphics.drawable.BitmapDrawable(ctx.resources, it) }
        d?.let {
            val b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            it.setBounds(0, 0, 32, 32); it.draw(Canvas(b)); b
        }
    } catch (_: Exception) { null }

    fun classify(b: Bitmap): Read? {
        val w = b.width; val h = b.height
        val bg = b.getPixel(0, 0)
        fun fg(x: Int, y: Int): Boolean {
            val p = b.getPixel(x, y)
            val a = Color.alpha(p)
            if (Color.alpha(bg) < 40) return a > 140                                    // arrow on transparent
            val d = abs(Color.red(p) - Color.red(bg)) + abs(Color.green(p) - Color.green(bg)) + abs(Color.blue(p) - Color.blue(bg))
            return a > 140 && d > 120                                                     // arrow on a colour
        }
        var x0 = w; var x1 = -1; var y0 = h; var y1 = -1; var count = 0
        val m = Array(h) { y -> BooleanArray(w) { x -> fg(x, y) } }
        for (y in 0 until h) for (x in 0 until w) if (m[y][x]) { count++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        if (count < 20 || x1 - x0 < 3 || y1 - y0 < 3) return null
        val bw = (x1 - x0 + 1).toFloat(); val bh = (y1 - y0 + 1).toFloat()
        fun cx(ya: Int, yb: Int): Float? {
            var sx = 0f; var n = 0
            for (y in ya..yb) for (x in x0..x1) if (m[y][x]) { sx += x; n++ }
            return if (n == 0) null else sx / n
        }
        val third = ((y1 - y0) / 3).coerceAtLeast(1)
        val stem = cx(y1 - third / 2, y1) ?: return null                                // bottom: where the stem starts
        val top = cx(y0, y0 + third) ?: return null                                     // top: where the arrow ends up
        val mid = cx(y0 + third, y1 - third) ?: stem
        val dx = (top - stem) / bw                                                       // − = left, + = right
        val midDx = (mid - stem) / bw
        val hash = hash(m, x0, y0, bw, bh)
        KNOWN[hash]?.let { return Read(it, hash, dx, "known") }
        val dir = when {
            abs(dx) < .08 && abs(midDx) < .08 -> Dir.STRAIGHT
            dx <= -.30 -> Dir.LEFT
            dx >= .30 -> Dir.RIGHT
            dx < -.08 -> Dir.SLIGHT_LEFT
            dx > .08 -> Dir.SLIGHT_RIGHT
            // the top is centred but the middle bulges out: a U-turn or a roundabout loop, not something to guess
            else -> Dir.UNKNOWN
        }
        return Read(dir, hash, dx, "top %.2f mid %.2f".format(dx, midDx))
    }

    /** 16×16 shape of the arrow, scaled to its own box, as hex: the same picture always gives the same hash. */
    private fun hash(m: Array<BooleanArray>, x0: Int, y0: Int, bw: Float, bh: Float): String {
        val sb = StringBuilder()
        for (gy in 0 until 16) {
            var row = 0
            for (gx in 0 until 16) {
                val x = (x0 + (gx + .5f) * bw / 16).toInt().coerceIn(0, m[0].size - 1)
                val y = (y0 + (gy + .5f) * bh / 16).toInt().coerceIn(0, m.size - 1)
                if (m[y][x]) row = row or (1 shl gx)
            }
            sb.append("%04x".format(row))
        }
        return Integer.toHexString(sb.toString().hashCode()).padStart(8, '0')
    }

    private fun save(ctx: Context, b: Bitmap, r: Read, title: String, text: String) {
        if (!saved.add(r.hash)) return
        try {
            val dir = File(ctx.getExternalFilesDir(null), "notes/maps_icons").apply { mkdirs() }
            val f = File(dir, "${r.hash}.png")
            if (!f.exists()) {
                f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(dir, "icons.tsv").appendText("${r.hash}\t${r.dir}\t${r.note}\t$title\t$text\n")
                Bike.log("[maps] new arrow picture ${r.hash}: read as ${r.dir} (${r.note})")
            }
        } catch (_: Exception) {}
    }
}
