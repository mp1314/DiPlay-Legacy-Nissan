package com.shilapi.xcertplay.bluetooth

/** A nearby device reported by an active discovery (inquiry) session. */
data class DiscoveredBluetoothDevice(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val classOfDevice: Int,
)

/** Outcome of an outgoing pairing (bonding) attempt initiated from this side. */
sealed class BluetoothPairResult {
    object Success : BluetoothPairResult()
    data class Failed(val reason: String) : BluetoothPairResult()
}

/**
 * Optional capability (src15, nissan custom line): a Bluetooth stack that can
 * actively search for nearby devices — "the car finds the phone" — and
 * initiate pairing from this side.
 *
 * Only stacks that can do this implement the interface (today: the USB dongle
 * stack). The built-in Android / HSAE stacks deliberately do not: their
 * discovery and bonding already live behind the system Bluetooth APIs used by
 * the pairing page's built-in mode, so callers must treat a missing
 * implementation as "not supported" rather than an error.
 */
interface CarPlayBluetoothDiscovery {

    /** True when this stack can run discovery right now (hardware present). */
    fun isDiscoverySupported(): Boolean

    /**
     * Start discovery. [onDevice] fires once per newly seen device (and again
     * if its name only becomes known later); [onFinished] fires exactly once,
     * with cancelled=true when [stopDiscovery] (or a shutdown) ended the run.
     * Callbacks may arrive on background threads. Returns false when discovery
     * could not be started (stack off, already running, hardware error).
     */
    fun startDiscovery(
        onDevice: (DiscoveredBluetoothDevice) -> Unit,
        onFinished: (cancelled: Boolean) -> Unit,
    ): Boolean

    /** Stop an active discovery; a no-op when none is running. */
    fun stopDiscovery()

    fun isDiscoveryActive(): Boolean

    /**
     * Initiate pairing (bonding) with [address] from this side. BLOCKING —
     * a full attempt can take tens of seconds (the driver may have to confirm
     * on the phone); call from a background thread. On success the device
     * counts as paired on this stack ([isPaired] / [pairedDevices]).
     */
    fun pairDevice(address: String): BluetoothPairResult

    fun isPaired(address: String): Boolean

    /** Devices already paired on this stack. */
    fun pairedDevices(): List<CarPlayBluetoothDevice>
}
