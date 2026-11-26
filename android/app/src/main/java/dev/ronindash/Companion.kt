package dev.ronindash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import java.util.regex.Pattern

/**
 * Android watches for the bike itself (companion-device presence). When the ignition comes on it wakes this service, which
 * starts the ride service; when the bike goes away the ride service saves the trip and stops. No process, no memory and
 * nothing scheduled while the bike is off; it survives reboots, and the app never has to be opened.
 */
class CompanionService : CompanionDeviceService() {
    @Deprecated("API 31–32")
    override fun onDeviceAppeared(address: String) = appeared(address)

    @Deprecated("API 31–32")
    override fun onDeviceDisappeared(address: String) = gone(address)

    override fun onDeviceAppeared(info: AssociationInfo) = appeared(info.deviceMacAddress?.toString() ?: "")

    override fun onDeviceDisappeared(info: AssociationInfo) = gone(info.deviceMacAddress?.toString() ?: "")

    private fun appeared(address: String) {
        Bike.log("[companion] bike appeared $address → starting")
        if (!BikeService.running) BikeService.start(this)
    }

    private fun gone(address: String) {
        Bike.log("[companion] bike gone $address")
        Bike.service?.bikeGone()
    }
}

object CompanionLink {
    /** Show Android's own "Allow Ronin Dash to access your bike?" dialog for a nearby TVS cluster. */
    fun associate(ctx: Context, launch: (IntentSender) -> Unit, failed: (String) -> Unit) {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return failed("This phone has no companion-device support")
        val filter = BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile("TVS.*")).build()
        val req = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        cdm.associate(req, object : CompanionDeviceManager.Callback() {
            @Deprecated("API 31–32")
            override fun onDeviceFound(sender: IntentSender) = launch(sender)
            override fun onAssociationPending(sender: IntentSender) = launch(sender)
            override fun onFailure(error: CharSequence?) = failed(error?.toString() ?: "No bike found. Is the ignition on?")
        }, null)
    }

    /** Result of the dialog: remember the bike, watch for it, and pair (the bike shows the code) if needed. */
    @SuppressLint("MissingPermission")
    fun onChosen(ctx: Context, data: Intent?): String? {
        val prefs = Prefs(ctx)
        var mac: String? = null; var name: String? = null; var id = 0
        if (Build.VERSION.SDK_INT >= 33) {
            val info = data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            mac = info?.deviceMacAddress?.toString()?.uppercase(); name = info?.displayName?.toString(); id = info?.id ?: 0
        }
        if (mac == null) {
            @Suppress("DEPRECATION") val r = data?.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
            @Suppress("DEPRECATION") val d = r?.device ?: data?.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
            mac = d?.address; name = try { d?.name } catch (_: SecurityException) { null }
        }
        if (mac == null) return null
        prefs.mac = mac; prefs.bikeName = name ?: prefs.bikeName.ifBlank { "TVS" }; prefs.associationId = id
        observe(ctx, mac)
        try {
            val dev = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(mac)
            if (dev.bondState != BluetoothDevice.BOND_BONDED) Bike.log("[pair] createBond=${dev.createBond()} (type the code shown on the bike)")
        } catch (e: Exception) { Bike.log("[pair] ${e.message}") }
        if (!BikeService.running) BikeService.start(ctx) else Bike.service?.reconnect()
        return mac
    }

    @SuppressLint("MissingPermission")
    fun observe(ctx: Context, mac: String) {
        try {
            @Suppress("DEPRECATION")
            ctx.getSystemService(CompanionDeviceManager::class.java)?.startObservingDevicePresence(mac)
            Bike.log("[companion] watching for $mac")
        } catch (e: Exception) { Bike.log("[companion] can't watch: ${e.message}") }
    }

    /** Already associated with the chosen bike? */
    fun isAssociated(ctx: Context): Boolean {
        val mac = Prefs(ctx).mac ?: return false
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.any { it.deviceMacAddress?.toString().equals(mac, true) }
            else @Suppress("DEPRECATION") cdm.associations.any { it.equals(mac, true) }
        } catch (_: Exception) { false }
    }
}
