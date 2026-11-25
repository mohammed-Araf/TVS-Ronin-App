package dev.ronindash

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Telephony

/** Reads SMS / WhatsApp / Google Maps notifications (the user grants "Notification access" in Settings). */
class NotifListener : NotificationListenerService() {

    companion object {
        const val MAPS = "com.google.android.apps.maps"
        private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val SMS_APPS = setOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging")
    }

    private lateinit var prefs: Prefs
    private val lastSeen = HashMap<String, String>()

    override fun onListenerConnected() {
        prefs = Prefs(this)
        recount()
    }

    private fun isMessageApp(pkg: String): Boolean {
        if (!::prefs.isInitialized) prefs = Prefs(this)
        if (prefs.whatsapp && pkg in WHATSAPP) return true
        if (prefs.sms && (pkg in SMS_APPS || pkg == Telephony.Sms.getDefaultSmsPackage(this))) return true
        return false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        if (sbn.packageName == MAPS) {
            val isNav = n.category == Notification.CATEGORY_NAVIGATION || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
            if (!isNav) return
            val raw = "title=[$title] text=[$text] sub=[$sub]"
            if (Bike.lastMapsRaw.value != raw) { Bike.lastMapsRaw.value = raw; Bike.log("[maps] $raw") }
            val info = NavParser.parse(title, text, sub) ?: return
            Bike.nav.value = info
            Bike.service?.onNav(info)
            return
        }

        if (!isMessageApp(sbn.packageName)) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) { recount(); return }
        val sig = "$title|$text|${n.`when`}"
        if (lastSeen[sbn.key] == sig) return // same notification re-posted (e.g. marked read elsewhere)
        lastSeen[sbn.key] = sig
        val sender = title.replace(Regex("""\s*\(\d+ (?:new )?messages?\)\s*$"""), "").substringAfter(": ")
        Bike.log("[msg] ${sbn.packageName.substringAfterLast('.')} from $sender")
        recount()
        Bike.service?.onMessage(sender)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSeen.remove(sbn.key)
        if (sbn.packageName == MAPS) {
            val n = sbn.notification
            if (n != null && (n.category == Notification.CATEGORY_NAVIGATION || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0)) {
                Bike.nav.value = null
                Bike.service?.onNav(null)
                Bike.log("[maps] navigation ended")
            }
            return
        }
        if (isMessageApp(sbn.packageName)) recount()
    }

    /** Unread messages = message notifications still in the shade (clears when the rider reads them). */
    private fun recount() {
        val list = try { activeNotifications } catch (_: Exception) { null } ?: return
        Bike.unreadMessages.value = list.count {
            isMessageApp(it.packageName) && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        }
    }
}
