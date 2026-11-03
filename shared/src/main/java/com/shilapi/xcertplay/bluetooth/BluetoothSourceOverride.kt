package com.shilapi.xcertplay.bluetooth

import android.content.Context

/**
 * src14 (nissan custom line): the driver's manual override for which Bluetooth
 * stack CarPlay uses, set from the home function bar's Bluetooth item.
 *
 * Default is [FOLLOW_AUTO]: the long-standing behaviour — a plugged-in USB
 * Bluetooth dongle wins, otherwise the built-in stack is used. [FORCE_BUILTIN]
 * excludes the dongle from every connection decision even while it stays
 * plugged in; [FORCE_USB] pins the dongle as the selected source (it still
 * only takes effect while a dongle is actually present).
 *
 * Stored in its own SharedPreferences file, following the small-settings
 * object pattern used elsewhere in this module (e.g. CarHotspotSettings).
 */
object BluetoothSourceOverride {
    const val FOLLOW_AUTO = 0
    const val FORCE_BUILTIN = 1
    const val FORCE_USB = 2

    private const val PREFS_NAME = "diplay_bt_source"
    private const val KEY_OVERRIDE = "bt_source_override"

    fun load(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_OVERRIDE, FOLLOW_AUTO)

    fun save(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_OVERRIDE, value).apply()
    }

    /** The USB dongle stack may take part in connection decisions. */
    fun usbAllowed(context: Context): Boolean = load(context) != FORCE_BUILTIN

    /** The source CarPlay will effectively use, given whether a dongle is plugged in. */
    fun effectiveUsb(context: Context, donglePresent: Boolean): Boolean =
        usbAllowed(context) && donglePresent
}
