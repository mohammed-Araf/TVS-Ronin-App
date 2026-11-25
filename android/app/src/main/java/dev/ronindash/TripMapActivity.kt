package dev.ronindash

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import java.io.File

/**
 * Full-screen 3D trip map (assets/trip.html, MapLibre + free satellite/terrain tiles).
 * Extra "share" = true opens the share preview sheet straight away (Trips list → Share).
 */
class TripMapActivity : Activity() {
    private lateinit var web: WebView
    private var sheetOpen = false
    private var backCb: Any? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: return finish()
        val json = try { TripStore.mapJson(this, id) } catch (e: Exception) { return finish() }
        val km = try { JSONObject(File(getExternalFilesDir(null), "trips/$id.json").readText()).optDouble("km", 0.0) } catch (e: Exception) { 0.0 }
        val openShare = intent.getBooleanExtra("share", false)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        val prefs = Prefs(this)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = when (prefs.theme) { "dark" -> true; "light" -> false; else -> night }
        val accent = "#%06X".format(ACCENTS.getOrElse(prefs.accent) { ACCENTS[0] }.second.toInt() and 0xFFFFFF)
        web.setBackgroundColor(if (dark) 0xFF0F1012.toInt() else 0xFFF6F6F7.toInt())
        web.addJavascriptInterface(object {
            @JavascriptInterface fun data(): String = json
            @JavascriptInterface fun theme(): String = """{"dark":$dark,"accent":"$accent","share":$openShare}"""
            @JavascriptInterface fun shareImage(b64: String) {
                val png = Base64.decode(b64, Base64.DEFAULT)
                runOnUiThread { ShareCard.shareBytes(this@TripMapActivity, id, png, km) }
            }
            @JavascriptInterface fun saveImage(b64: String): Boolean =
                ShareCard.saveToGallery(this@TripMapActivity, id, Base64.decode(b64, Base64.DEFAULT))
            @JavascriptInterface fun sheet(open: Boolean) { runOnUiThread { setSheet(open) } }
        }, "Android")
        val html = assets.open("trip.html").bufferedReader().use { it.readText() }
        web.loadDataWithBaseURL("https://ronindash.local/", html, "text/html", "utf-8", null)
        setContentView(web)
    }

    /** While the share sheet is open, Back closes the sheet instead of leaving the map. */
    private fun setSheet(open: Boolean) {
        sheetOpen = open
        if (Build.VERSION.SDK_INT >= 33) {
            val d = onBackInvokedDispatcher
            if (open && backCb == null) {
                val cb = OnBackInvokedCallback { closeSheet() }
                d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb); backCb = cb
            } else if (!open && backCb != null) {
                d.unregisterOnBackInvokedCallback(backCb as OnBackInvokedCallback); backCb = null
            }
        }
    }

    private fun closeSheet() { web.evaluateJavascript("window.closeShare && window.closeShare()", null) }

    @Deprecated("Pre-predictive-back path (Android 12 and apps without the opt-in)")
    override fun onBackPressed() {
        if (sheetOpen) closeSheet() else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
