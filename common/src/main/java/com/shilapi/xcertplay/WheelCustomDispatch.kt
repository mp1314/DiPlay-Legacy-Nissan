package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlayKnobState

/**
 * FreeAuto-style dispatch for learned custom wheel keys (WheelZoomSettings roles 8..25).
 *
 * This is the fix2 extraction of WheelKeyService.dispatchCustomKey into its own class
 * (hand-patching the original inline version tripped a VerifyError, so the dispatch now
 * lives here and the service only keeps the call-site hook).
 *
 * Behavior notes vs the v0.2.69 release APK's smali:
 * - VOLUME_MUTE toggles correctly (muted -> unmute, unmuted -> mute). The release APK's
 *   smali had the two directions swapped, which made the mute key a silent no-op.
 * - The call site in WheelKeyService gates on learning == null (upstream source design),
 *   so learned keys fire during normal driving. The release APK's smali gates on
 *   learning != null, which would only ever dispatch while a learn session is open.
 */
object WheelCustomDispatch {
    private const val TAG = "DiPlay-WheelKeys"

    private var audioManager: AudioManager? = null

    private fun audioManager(context: Context): AudioManager =
        audioManager ?: (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .also { audioManager = it }

    /**
     * Returns true when the key was handled and must not reach the car,
     * false when the press is consumed silently without an action.
     */
    fun dispatch(
        service: WheelKeyService,
        context: Context,
        key: WheelKey,
        down: Boolean,
    ): Boolean {
        if (!WheelZoomSettings.customKeys(context)) return false
        val role = WheelZoomSettings.customRoleOf(context, key) ?: return false
        return when (role) {
            WheelZoomSettings.Role.VOLUME_UP, WheelZoomSettings.Role.VOLUME_DOWN -> {
                if (down) {
                    val dir = if (role == WheelZoomSettings.Role.VOLUME_UP)
                        AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    runCatching {
                        audioManager(context).adjustStreamVolume(
                            AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI
                        )
                    }
                }
                true
            }
            WheelZoomSettings.Role.VOLUME_MUTE -> {
                if (down) runCatching {
                    val am = audioManager(context)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
                        am.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            if (muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
                            AudioManager.FLAG_SHOW_UI,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        am.setStreamMute(
                            AudioManager.STREAM_MUSIC,
                            !am.isStreamMute(AudioManager.STREAM_MUSIC),
                        )
                    }
                }
                true
            }
            WheelZoomSettings.Role.PLAY_PAUSE ->
                if (down) CarPlayMediaKeys.handleWheelMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) else true
            WheelZoomSettings.Role.MEDIA_PREVIOUS ->
                if (down) CarPlayMediaKeys.handleWheelMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) else true
            WheelZoomSettings.Role.MEDIA_NEXT ->
                if (down) CarPlayMediaKeys.handleWheelMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) else true
            WheelZoomSettings.Role.CALL_ANSWER, WheelZoomSettings.Role.CALL_HANGUP -> {
                if (!down) return true
                val controller = CarPlayBackgroundSession.snapshot()?.controller ?: return false
                val sent = if (role == WheelZoomSettings.Role.CALL_ANSWER) controller.answerCall()
                else controller.endCall()
                Log.i(TAG, "custom call key $role sent=$sent")
                true
            }
            WheelZoomSettings.Role.DPAD_UP -> dpadKnob(service.knob, 0, -1, down)
            WheelZoomSettings.Role.DPAD_DOWN -> dpadKnob(service.knob, 0, 1, down)
            WheelZoomSettings.Role.DPAD_LEFT -> dpadKnob(service.knob, -1, 0, down)
            WheelZoomSettings.Role.DPAD_RIGHT -> dpadKnob(service.knob, 1, 0, down)
            WheelZoomSettings.Role.DPAD_SELECT -> {
                if (!down) return true
                service.knob(AirPlayKnobState(select = true))
            }
            WheelZoomSettings.Role.DPAD_BACK -> {
                if (!down) return true
                service.knob(AirPlayKnobState(back = true))
            }
            // Shortcut roles are learn-and-store only for now; no dispatch hook exists yet.
            WheelZoomSettings.Role.SHORTCUT_MAPS, WheelZoomSettings.Role.SHORTCUT_MUSIC,
            WheelZoomSettings.Role.SHORTCUT_PHONE, WheelZoomSettings.Role.SHORTCUT_SETTINGS -> {
                if (down) Log.i(TAG, "custom shortcut key $role pressed (dispatch not wired)")
                true
            }
            else -> false
        }
    }

    /** Send a D-pad nudge through the CarPlay knob channel. */
    private fun dpadKnob(
        knob: (AirPlayKnobState) -> Boolean,
        x: Int,
        y: Int,
        down: Boolean,
    ): Boolean {
        if (!down) return true
        return knob(AirPlayKnobState(x = x, y = y))
    }
}
