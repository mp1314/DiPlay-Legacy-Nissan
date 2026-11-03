package com.shilapi.xcertplay.bluetooth

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.usbbt.UsbBtBluetoothAdapter

class HybridBluetoothAdapter(
    val hsae: HsaeBluetoothAdapter,
    val android: AndroidBluetoothAdapter,
    /** USB dongle stack; preferred for data channels when plugged in. */
    val usbDongle: UsbBtBluetoothAdapter? = null,
    /**
     * src14 (nissan custom line): the driver's manual Bluetooth-source
     * override. When this returns false the dongle is excluded from every
     * connection decision below, as if it were not plugged in. Defaults to
     * always-allowed so existing constructions keep the automatic behaviour.
     */
    private val usbAllowed: () -> Boolean = { true },
) : CarPlayBluetooth, CarPlayBluetoothDiscovery {

    companion object {
        private const val TAG = "HybridBluetoothAdapter"
    }

    /** The dongle is present *and* the manual override lets CarPlay use it. */
    private fun dongleUsable(): Boolean = usbDongle?.isSupported() == true && usbAllowed()

    override fun isSupported(): Boolean =
        hsae.isSupported() || android.isSupported() || usbDongle?.isSupported() == true

    override fun isEnabled(): Boolean {
        val dongle = usbDongle
        if (dongle?.isSupported() == true && usbAllowed()) return dongle.isEnabled()
        if (hsae.isSupported() && hsae.isEnabled()) return true
        return android.isEnabled()
    }

    override fun enable(): Boolean {
        var ok = false
        if (hsae.isSupported()) {
            ok = hsae.enable()
        }
        if (android.enable()) ok = true
        // USB dongle: enable whenever one is plugged in. Previously this only
        // ran when neither HSAE nor Android existed, so on T3 (HSAE present
        // but audio-only) the dongle was never enabled and wireless could
        // never reach it (P0-1). Plugging in a dongle is itself the opt-in.
        val dongle = usbDongle
        if (dongle?.isSupported() == true && usbAllowed()) {
            return dongle.enable()
        }
        return ok
    }

    override fun disable() {
        usbDongle?.disable()
    }

    override fun getAdapterName(): String {
        val parts = mutableListOf<String>()
        if (hsae.isSupported()) parts.add("HSAE")
        if (android.isSupported()) parts.add("Android")
        if (dongleUsable()) parts.add("USB")
        return if (parts.isEmpty()) android.getAdapterName()
        else "Hybrid(${parts.joinToString("+")})"
    }

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        // Merge all stacks, dedupe by MAC. Previously this returned the first
        // non-empty list, so an iPhone paired on the dongle was invisible
        // when HSAE had any (audio-only) paired device (P1-1).
        val seen = HashSet<String>()
        val merged = ArrayList<CarPlayBluetoothDevice>()
        fun addAll(devices: List<CarPlayBluetoothDevice>) {
            for (device in devices) {
                val key = device.address.lowercase()
                if (seen.add(key)) merged.add(device)
            }
        }
        if (hsae.isSupported()) runCatching { addAll(hsae.getPairedDevices()) }
        runCatching { addAll(android.getPairedDevices()) }
        // Manual override (src14): a forced built-in source also hides the
        // dongle's paired devices, so the iPhone is chosen on the built-in
        // adapter it will actually be reached through.
        if (usbAllowed()) usbDongle?.let { runCatching { addAll(it.getPairedDevices()) } }
        return merged
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        // Dongle first: it is the data-capable stack. HSAE may report a
        // connected audio-only device that cannot carry iAP2.
        if (dongleUsable()) usbDongle?.let { runCatching { it.getConnectedDevice() } }?.getOrNull()?.let { return it }
        android.getConnectedDevice()?.let { return it }
        if (hsae.isSupported()) {
            runCatching { hsae.getConnectedDevice() }.getOrNull()?.let { return it }
        }
        return null
    }

    override fun localAddress(context: Context): String? {
        // Dongle first: its BD_ADDR is the one the iPhone must pair with for
        // the data channel. Falling back to HSAE's address would pair the
        // iPhone to the audio-only module.
        if (dongleUsable()) usbDongle?.let { runCatching { it.localAddress(context) } }?.getOrNull()
            ?.takeIf { !it.isNullOrBlank() }?.let { return it }
        if (hsae.isSupported()) {
            val hsaeAddr = hsae.localAddress(context)
            if (!hsaeAddr.isNullOrBlank()) return hsaeAddr
        }
        android.localAddress(context)?.let { return it }
        return null
    }

    // ------------------------------------------------------------------
    // CarPlayBluetoothDiscovery (src15): "car finds phone" lives only on
    // the USB dongle stack. The manual source override gates it exactly
    // like every other dongle decision: a forced built-in source means
    // the dongle does not exist as far as callers are concerned.
    // ------------------------------------------------------------------

    override fun isDiscoverySupported(): Boolean = dongleUsable()

    override fun startDiscovery(
        onDevice: (DiscoveredBluetoothDevice) -> Unit,
        onFinished: (cancelled: Boolean) -> Unit,
    ): Boolean {
        val dongle = usbDongle ?: return false
        if (!dongleUsable()) return false
        return dongle.startDiscovery(onDevice, onFinished)
    }

    override fun stopDiscovery() {
        usbDongle?.stopDiscovery()
    }

    override fun isDiscoveryActive(): Boolean = usbDongle?.isDiscoveryActive() == true

    override fun pairDevice(address: String): BluetoothPairResult {
        val dongle = usbDongle
            ?: return BluetoothPairResult.Failed("USB Bluetooth unavailable")
        if (!dongleUsable()) return BluetoothPairResult.Failed("USB Bluetooth unavailable")
        return dongle.pairDevice(address)
    }

    override fun isPaired(address: String): Boolean = usbDongle?.isPaired(address) == true

    override fun pairedDevices(): List<CarPlayBluetoothDevice> =
        if (usbAllowed()) usbDongle?.getPairedDevices().orEmpty() else emptyList()

    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        val dongle = usbDongle
        if (dongle?.isSupported() == true && usbAllowed()) {
            if (!dongle.isEnabled()) throw java.io.IOException("USB Bluetooth not enabled")
            // An iAP2 advertisement and bond belong to one local adapter address.
            // Switching to GOC/HSAE cannot resume the dongle's connection.
            return dongle.openRfcommStream(deviceAddress, uuid)
        }
        var failure: Exception? = null
        if (android.isSupported() && android.isEnabled()) {
            try {
                return android.openRfcommStream(deviceAddress, uuid)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (error: Exception) {
                failure = error
            }
        }
        if (hsae.isSupported()) return hsae.openRfcommStream(deviceAddress, uuid)
        throw java.io.IOException("Bluetooth RFCOMM unavailable: ${failure?.message ?: "no Bluetooth stack available"}", failure)
    }
}
