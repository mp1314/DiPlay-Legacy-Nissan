package com.shilapi.xcertplay

import android.annotation.TargetApi
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * src13: 主页「蓝牙歌词区」的数据源（尽力而为）。
 *
 * 通过 [MediaSessionManager.getActiveSessions]（以本应用的媒体按键接收器
 * [LegacyMediaKeyReceiver] 为参数）遍历活跃媒体会话，从 MediaMetadata 里找歌词：
 * 先看 description，再看自定义键 lyrics/lyric/lrc 等。取到的文本若是 LRC
 * （带 [mm:ss.xx] 时间轴），按 PlaybackState 位置选当前行/下一行；否则整段当当前行。
 *
 * 现实约束：AVRCP 标准只传歌名/歌手/专辑，不定义歌词字段；能否拿到歌词取决于
 * 原车蓝牙 App / 播放器是否把歌词塞进媒体元数据。拿不到时上层保持整区隐藏。
 *
 * 本类引用 API 21+ 的 media.session 类，只能在 realSdkInt() >= 21 时构造
 * （与 BluetoothCompat 隔离 Dalvik 校验问题的同一做法）。
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class HomeLyricsSession(context: Context, private val onChanged: () -> Unit) {

    private val appContext = context.applicationContext
    private val sessionManager: MediaSessionManager? =
        runCatching { appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager }
            .getOrNull()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    private var started = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChanged()
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChanged()
        override fun onSessionDestroyed() = onChanged()
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { updated ->
            resubscribe(updated.orEmpty())
            onChanged()
        }

    fun start() {
        if (started) return
        started = true
        val manager = sessionManager ?: return
        val component = ComponentName(appContext, LegacyMediaKeyReceiver::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component, mainHandler)
        }
        resubscribe(runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList()))
    }

    fun stop() {
        if (!started) return
        started = false
        resubscribe(emptyList())
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
    }

    private fun resubscribe(updated: List<MediaController>) {
        val old = controllers
        if (old === updated || old == updated) return
        old.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = updated
        controllers.forEach { runCatching { it.registerCallback(controllerCallback, mainHandler) } }
    }

    /**
     * 当前应显示的歌词：first=当前行，second=下一行（可为 null）。
     * 没有任何会话提供歌词时返回 null（上层隐藏整区）。
     */
    fun currentLines(): Pair<String, String?>? {
        for (controller in controllers) {
            val lines = runCatching { linesFor(controller) }.getOrNull()
            if (lines != null) return lines
        }
        // 回调可能还没跟上：直接向系统再要一次最新会话列表兜底。
        val manager = sessionManager ?: return null
        val component = ComponentName(appContext, LegacyMediaKeyReceiver::class.java)
        val fresh = runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
        for (controller in fresh) {
            val lines = runCatching { linesFor(controller) }.getOrNull()
            if (lines != null) return lines
        }
        return null
    }

    private fun linesFor(controller: MediaController): Pair<String, String?>? {
        val metadata = controller.metadata ?: return null
        val raw = extractLyricsText(metadata) ?: return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        val lrc = parseLrc(text)
        if (lrc == null) {
            // 普通文本：整段当当前行（显示层负责单行省略）。
            return text.replace('\n', ' ') to null
        }
        val positionMs = currentPositionMs(controller.playbackState)
        var currentIndex = lrc.indexOfLast { it.first <= positionMs }
        if (currentIndex < 0) currentIndex = 0
        val current = lrc[currentIndex].second
        if (current.isBlank()) {
            // 当前行是间奏空行：不显示整区（等下一句）。
            val next = lrc.getOrNull(currentIndex + 1)?.second?.takeIf { it.isNotBlank() }
            return if (next != null) next to null else null
        }
        val next = lrc.getOrNull(currentIndex + 1)?.second?.takeIf { it.isNotBlank() }
        return current to next
    }

    private fun currentPositionMs(state: PlaybackState?): Long {
        state ?: return 0L
        val base = state.position
        if (state.state != PlaybackState.STATE_PLAYING) return base
        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        if (elapsed <= 0) return base
        return base + (elapsed * state.playbackSpeed).toLong()
    }

    private fun extractLyricsText(metadata: MediaMetadata): String? {
        val description = runCatching { metadata.description }.getOrNull()
        val fromDescription = listOf(
            runCatching { description?.description?.toString() }.getOrNull(),
            runCatching { description?.subtitle?.toString() }.getOrNull(),
        )
        for (candidate in fromDescription) {
            if (!candidate.isNullOrBlank()) return candidate
        }
        for (key in LYRICS_KEYS) {
            val value = runCatching { metadata.getString(key) }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** 解析 LRC：返回按时间排序的 (时间ms, 行文本)；没有任何时间标签时返回 null。 */
    private fun parseLrc(text: String): List<Pair<Long, String>>? {
        val result = mutableListOf<Pair<Long, String>>()
        for (line in text.lineSequence()) {
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            val lyricText = line.replace(TIME_TAG, "").trim()
            for (tag in tags) {
                val minutes = tag.groupValues[1].toLongOrNull() ?: continue
                val seconds = tag.groupValues[2].toLongOrNull() ?: continue
                val fraction = tag.groupValues[3]
                val fractionMs = when (fraction.length) {
                    0 -> 0L
                    1 -> (fraction.toLongOrNull() ?: 0L) * 100
                    2 -> (fraction.toLongOrNull() ?: 0L) * 10
                    else -> (fraction.take(3).toLongOrNull() ?: 0L)
                }
                result += (minutes * 60_000L + seconds * 1_000L + fractionMs) to lyricText
            }
        }
        if (result.isEmpty()) return null
        result.sortBy { it.first }
        return result
    }

    private companion object {
        val TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")
        val LYRICS_KEYS = listOf(
            "lyrics", "lyric", "lrc", "LYRICS", "LYRIC",
            "android.media.metadata.LYRICS",
            "com.android.music.lyrics", "com.miui.player.lyrics",
        )
    }
}
