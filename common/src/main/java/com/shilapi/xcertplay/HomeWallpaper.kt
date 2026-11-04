// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.shilapi.xcertplay.host.R
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * src18: 主页壁纸，共 9 个选项。0-4 为代码绘制（配色与纹理照预览脚本
 * render_wallpapers.py 的 draw_bg 四个分支，基准画布 1024x600）；5-8 为内置二次元
 * 插画（assets/wallpapers 下的 webp 原文件），照 render_anime.py 做 cover 取景 +
 * 压暗 + 左侧暗角后烘进缓存位图。src20 起壁纸作用于 DiPlayActivity 的全部页面根
 * 背景（主页/设置/连接/配对/关于）；卡片、按钮、功能栏与文字颜色都不动。CarPlay
 * 投屏是独立的 CarPlayHostActivity，不在此列。默认 0=纯黑，各页面与 src19 一致。
 */
object HomeWallpaper {
    const val BLACK = 0
    const val MIDNIGHT = 1
    const val CARBON = 2
    const val STARROAD = 3
    const val GRAPHITE = 4
    const val NIGHT_SAKURA = 5
    const val UNDER_THE_STARS = 6
    const val CYBER_CITY = 7
    const val DUSK_SEASIDE = 8

    val ALL = listOf(
        BLACK, MIDNIGHT, CARBON, STARROAD, GRAPHITE,
        NIGHT_SAKURA, UNDER_THE_STARS, CYBER_CITY, DUSK_SEASIDE,
    )

    /** 5-8 号插画的 assets 路径（0-4 号代码绘制，无文件）。 */
    fun assetPath(style: Int): String? = when (style) {
        NIGHT_SAKURA -> "wallpapers/anime_sakura.webp"
        UNDER_THE_STARS -> "wallpapers/anime_stars.webp"
        CYBER_CITY -> "wallpapers/anime_cyber.webp"
        DUSK_SEASIDE -> "wallpapers/anime_sea.webp"
        else -> null
    }

    /**
     * 插画水平取景焦点（0=最左、1=最右；垂直恒居中）。数值按预览检查定版：
     * 尽量让人物靠右，避开右侧码速/三键区。代码壁纸不用，返回中心 0.5。
     */
    fun focusX(style: Int): Float = when (style) {
        NIGHT_SAKURA -> 0.38f
        UNDER_THE_STARS -> 0.42f
        CYBER_CITY -> 0.58f
        DUSK_SEASIDE -> 0.55f
        else -> 0.5f
    }

    /** 偏好里存了范围外的值（旧版本/手改）时回落到纯黑。 */
    fun normalize(style: Int): Int = if (style in ALL) style else BLACK

    fun preference(context: Context): Int = normalize(DiPlayPreferences.homeWallpaper(context))

    fun save(context: Context, style: Int) {
        DiPlayPreferences.saveHomeWallpaper(context, normalize(style))
    }

    fun displayName(context: Context, style: Int): String = context.getString(
        when (normalize(style)) {
            MIDNIGHT -> R.string.settings_home_wallpaper_midnight_blue
            CARBON -> R.string.settings_home_wallpaper_carbon_fiber
            STARROAD -> R.string.settings_home_wallpaper_starry_sky
            GRAPHITE -> R.string.settings_home_wallpaper_graphite
            NIGHT_SAKURA -> R.string.settings_home_wallpaper_night_sakura
            UNDER_THE_STARS -> R.string.settings_home_wallpaper_under_the_stars
            CYBER_CITY -> R.string.settings_home_wallpaper_cyber_city
            DUSK_SEASIDE -> R.string.settings_home_wallpaper_dusk_seaside
            else -> R.string.settings_home_wallpaper_black
        },
    )

    /** 与语言选择（AppLocale.showPicker）同款的单选弹窗：确认即保存，回调里刷新界面。 */
    fun showPicker(activity: Activity, onChanged: () -> Unit) {
        var selected = ALL.indexOf(preference(activity)).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_home_wallpaper)
            .setSingleChoiceItems(ALL.map { displayName(activity, it) }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val next = ALL[selected]
                if (next != preference(activity)) {
                    save(activity, next)
                    onChanged()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}

/**
 * 壁纸的纯计算与位图绘制。颜色常量逐一对应 render_wallpapers.py（1024x600 基准）；
 * 纯函数（渐变插值、石墨灰阶、碳纤维色板、星点场）不依赖 Android 类，可直接单测。
 */
internal object HomeWallpaperArt {
    private const val BASE_WIDTH = 1024f
    private const val BASE_HEIGHT = 600f

    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = from shr shift and 0xFF
            val b = to shr shift and 0xFF
            return (a + (b - a) * clamped).roundToInt()
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** 午夜蓝对角渐变：t=0 → (10,24,46)，t=1 → (2,5,12)；t = 0.45*(x/W) + 0.55*(y/H)。 */
    fun midnightColor(t: Float): Int = lerpColor(0x0A182E, 0x02050C, t)

    /** 石墨灰径向渐变灰阶：v = 30 - 25t（脚本用 int() 截断），颜色取 (v, v+1, v+3)。 */
    fun graphiteShade(t: Float): Int =
        (30f - 25f * t.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)

    /** 碳纤维编织格：横向格底色 (26,26,30)、纵向格底色 (16,16,19)。 */
    fun carbonBase(horizontal: Boolean): Int = if (horizontal) 0x1A1A1E else 0x101013

    /** 格内高光条：横向格在顶部 (38,38,44)，纵向格在底部 (30,30,35)。 */
    fun carbonStrip(horizontal: Boolean): Int = if (horizontal) 0x26262C else 0x1E1E23

    data class Star(val x: Int, val y: Int, val alpha: Int, val size: Int)

    /** 星点场：固定种子（脚本同为 seed 7），同尺寸下位置稳定；170 颗，只落在上半部 55%。 */
    fun starField(width: Int, height: Int): List<Star> {
        val random = Random(7)
        val maxY = (height * 0.55f).toInt()
        return List(170) {
            Star(
                x = if (width > 0) random.nextInt(width) else 0,
                y = if (maxY > 0) random.nextInt(maxY + 1) else 0,
                alpha = random.nextInt(60, 191),
                size = if (random.nextFloat() < 0.85f) 1 else 2,
            )
        }
    }

    /** 插画可读性处理参数（render_anime.py）：整体压暗黑 alpha 26；左侧暗角 alpha 140 → 0。 */
    const val IMAGE_DARKEN_ALPHA = 26
    const val VIGNETTE_MAX_ALPHA = 140

    /** 暗角衰减终点占宽度的比例：脚本在 1024 宽下渐变到 x=640，即 0.625。 */
    const val VIGNETTE_END_FRACTION = 0.625f

    /** 左侧暗角在像素列 x 处的 alpha：脚本 int(140 * max(0, 1 - x/640))（按宽度缩放）。 */
    fun vignetteAlpha(x: Int, width: Int): Int {
        if (width <= 0) return 0
        val span = VIGNETTE_END_FRACTION * width
        return (VIGNETTE_MAX_ALPHA * max(0f, 1f - x / span)).toInt().coerceIn(0, VIGNETTE_MAX_ALPHA)
    }

    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * cover 取景（等价于脚本的 max 缩放 + 裁切）：源图按目标宽高比裁出最大窗口，
     * 水平位置由 focusX 决定（0=最左、1=最右），垂直恒居中。
     */
    fun centerCrop(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int, focusX: Float): Crop {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            return Crop(0, 0, max(srcWidth, 0), max(srcHeight, 0))
        }
        val dstAspect = dstWidth.toFloat() / dstHeight
        return if (srcWidth.toFloat() / srcHeight > dstAspect) {
            val cropWidth = (srcHeight * dstAspect).roundToInt().coerceIn(1, srcWidth)
            val left = ((srcWidth - cropWidth) * focusX.coerceIn(0f, 1f)).roundToInt()
                .coerceIn(0, srcWidth - cropWidth)
            Crop(left, 0, cropWidth, srcHeight)
        } else {
            val cropHeight = (srcWidth / dstAspect).roundToInt().coerceIn(1, srcHeight)
            val top = ((srcHeight - cropHeight) / 2f).roundToInt().coerceIn(0, srcHeight - cropHeight)
            Crop(0, top, srcWidth, cropHeight)
        }
    }

    fun render(width: Int, height: Int, style: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        when (style) {
            HomeWallpaper.MIDNIGHT -> drawMidnight(canvas, width, height)
            HomeWallpaper.CARBON -> drawCarbon(canvas, width, height)
            HomeWallpaper.STARROAD -> drawStarroad(canvas, width, height)
            HomeWallpaper.GRAPHITE -> drawGraphite(canvas, width, height)
            else -> canvas.drawColor(Color.BLACK)
        }
        return bitmap
    }

    /**
     * 5-8 号插画：解码 assets 原图 → 按焦点 cover 取景 → 整体压暗 → 左侧渐变暗角，
     * 全部烘进目标尺寸位图（由 HomeWallpaperCache 按尺寸+样式缓存，不重复解码）。
     * 资源缺失/解码失败返回 null，调用方回落纯黑。
     */
    fun renderImage(context: Context, width: Int, height: Int, style: Int): Bitmap? {
        val path = HomeWallpaper.assetPath(style) ?: return null
        val source = try {
            context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } ?: return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val crop = centerCrop(source.width, source.height, width, height, HomeWallpaper.focusX(style))
        val srcRect = Rect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, srcRect, Rect(0, 0, width, height), paint)
        source.recycle()
        canvas.drawColor(Color.argb(IMAGE_DARKEN_ALPHA, 0, 0, 0))
        val vignette = Paint().apply {
            shader = LinearGradient(
                0f, 0f, VIGNETTE_END_FRACTION * width, 0f,
                Color.argb(VIGNETTE_MAX_ALPHA, 0, 0, 0), Color.argb(0, 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette)
        return bitmap
    }

    private fun rgb(packed: Int): Int =
        Color.rgb(packed shr 16 and 0xFF, packed shr 8 and 0xFF, packed and 0xFF)

    private fun drawMidnight(canvas: Canvas, w: Int, h: Int) {
        // 脚本的 t 是按分数坐标加权的（0.45*u + 0.55*v），不是像素对角线；
        // 渐变终点取方向 (0.45/W, 0.55/H) 的等值点，保证任意宽高下 t 分布与脚本一致。
        val dx = 0.45f / w
        val dy = 0.55f / h
        val denom = dx * dx + dy * dy
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, dx / denom, dy / denom,
                rgb(midnightColor(0f)), rgb(midnightColor(1f)), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        // 右下蓝色光晕：脚本椭圆 [0.45W,0.30H]-[1.15W,0.95H]、(70,130,220) alpha 46、强模糊。
        drawGlow(canvas, 0.80f * w, 0.625f * h, 0.35f * w, 0.325f * h,
            Color.argb(46, 70, 130, 220), Color.argb(0, 70, 130, 220))
    }

    private fun drawCarbon(canvas: Canvas, w: Int, h: Int) {
        canvas.drawColor(Color.rgb(9, 9, 11))
        val cell = max(8, (14f * w / BASE_WIDTH).roundToInt())
        val strip = max(1, (cell * 4f / 14f).roundToInt())
        val paint = Paint()
        var row = 0
        var y = 0
        while (y < h) {
            var col = 0
            var x = 0
            while (x < w) {
                val horizontal = (col + row) % 2 == 0
                paint.color = rgb(carbonBase(horizontal))
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), paint)
                paint.color = rgb(carbonStrip(horizontal))
                if (horizontal) {
                    canvas.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + strip).toFloat(), paint)
                } else {
                    canvas.drawRect(x.toFloat(), (y + cell - strip).toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), paint)
                }
                x += cell
                col++
            }
            y += cell
            row++
        }
        // 左上柔和压暗：脚本椭圆 [-0.3W,-0.6H]-[0.8W,0.5H]、黑 alpha 70、大模糊。
        drawGlow(canvas, 0.25f * w, -0.05f * h, 0.55f * w, 0.55f * h,
            Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0))
    }

    private fun drawStarroad(canvas: Canvas, w: Int, h: Int) {
        // 竖直渐变：逐行 (4+2t, 8+4t, 20+8t)，t = y/H。
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                Color.rgb(4, 8, 20), Color.rgb(6, 12, 28), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val scale = w / BASE_WIDTH
        val starPaint = Paint()
        for (star in starField(w, h)) {
            starPaint.color = Color.rgb(star.alpha, star.alpha, min(255, star.alpha + 20))
            val s = max(1, (star.size * scale).roundToInt()).toFloat()
            canvas.drawRect(star.x.toFloat(), star.y.toFloat(), star.x + s, star.y + s, starPaint)
        }
        val hy = 0.62f * h
        // 地平线光晕：脚本椭圆 [-0.2W,hy-60]-[1.2W,hy+90]、(90,150,235) alpha 60、模糊 45。
        drawGlow(canvas, 0.5f * w, hy + 15f * h / BASE_HEIGHT, 0.7f * w, 75f * h / BASE_HEIGHT,
            Color.argb(60, 90, 150, 235), Color.argb(0, 90, 150, 235))
        val linePaint = Paint().apply { color = Color.rgb(70, 110, 170) }
        val lineHeight = max(1f, h / BASE_HEIGHT)
        canvas.drawRect(0f, hy, w.toFloat(), hy + lineHeight, linePaint)
    }

    private fun drawGraphite(canvas: Canvas, w: Int, h: Int) {
        val cx = w / 2f
        val cy = 0.42f * h
        val maxRadius = (hypot(w.toDouble(), h.toDouble()) / 2).toFloat()
        val start = graphiteShade(0f)
        val end = graphiteShade(1f)
        val paint = Paint().apply {
            shader = RadialGradient(cx, cy, maxRadius,
                Color.rgb(start, start + 1, start + 3), Color.rgb(end, end + 1, end + 3),
                Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    /** 椭圆柔光：脚本用「实心椭圆 + 高斯模糊」做光晕，这里用同色 alpha 径向渐变等效（无硬边）。 */
    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, inner: Int, outer: Int) {
        if (rx <= 0f || ry <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(0f, 0f, rx, inner, outer, Shader.TileMode.CLAMP)
        }
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(1f, ry / rx)
        canvas.drawCircle(0f, 0f, rx, paint)
        canvas.restore()
    }
}

/**
 * 壁纸位图缓存：key =（宽、高、样式），只保留最近一张。主页重渲染（含码速/歌词驱动的
 * 状态刷新路径）时 Drawable 直接贴缓存位图，不重绘渐变/星点、也不重新解码插画原图。
 * 旧位图不主动 recycle——被替换掉的上一帧 View 可能还在绘制它，交给 GC 回收
 * （切换尺寸/样式是低频操作）。
 */
internal object HomeWallpaperCache {
    private var cachedKey: Triple<Int, Int, Int>? = null
    private var cachedBitmap: Bitmap? = null

    @Synchronized
    fun bitmap(context: Context, width: Int, height: Int, style: Int): Bitmap? {
        if (style == HomeWallpaper.BLACK || width <= 0 || height <= 0) return null
        val key = Triple(width, height, style)
        val existing = cachedBitmap
        if (cachedKey == key && existing != null && !existing.isRecycled) return existing
        val rendered = if (HomeWallpaper.assetPath(style) != null) {
            HomeWallpaperArt.renderImage(context, width, height, style)
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(Color.BLACK) }
        } else {
            HomeWallpaperArt.render(width, height, style)
        }
        cachedKey = key
        cachedBitmap = rendered
        return rendered
    }
}

/** 页面根布局的背景：按自身边界尺寸取缓存位图 1:1 贴图（只持有 applicationContext）。 */
internal class HomeWallpaperDrawable(context: Context, private val style: Int) : Drawable() {
    private val appContext: Context = context.applicationContext

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val bitmap = HomeWallpaperCache.bitmap(appContext, bounds.width(), bounds.height(), style)
        if (bitmap == null) canvas.drawColor(Color.BLACK)
        else canvas.drawBitmap(bitmap, null, bounds, null)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
