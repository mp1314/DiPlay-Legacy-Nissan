package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** src18: 壁纸纯函数的回归测试（数值逐一对应 render_wallpapers.py 的 draw_bg）。 */
class HomeWallpaperTest {

    @Test fun normalizeFallsBackToBlackForUnknownStyles() {
        assertEquals(HomeWallpaper.BLACK, HomeWallpaper.normalize(-1))
        assertEquals(HomeWallpaper.BLACK, HomeWallpaper.normalize(5))
        assertEquals(HomeWallpaper.BLACK, HomeWallpaper.normalize(Int.MAX_VALUE))
        for (style in HomeWallpaper.ALL) {
            assertEquals(style, HomeWallpaper.normalize(style))
        }
    }

    @Test fun midnightGradientMatchesPreviewEndpoints() {
        // 脚本：t=0 → (10,24,46)，t=1 → (2,5,12)。
        assertEquals(0x0A182E, HomeWallpaperArt.midnightColor(0f))
        assertEquals(0x02050C, HomeWallpaperArt.midnightColor(1f))
        // 中点逐通道取整插值，且整体单调变暗。
        assertEquals(0x060F1D, HomeWallpaperArt.midnightColor(0.5f))
        assertEquals(HomeWallpaperArt.midnightColor(1f), HomeWallpaperArt.midnightColor(2f))
        assertEquals(HomeWallpaperArt.midnightColor(0f), HomeWallpaperArt.midnightColor(-1f))
    }

    @Test fun graphiteShadeRunsFromThirtyToFive() {
        assertEquals(30, HomeWallpaperArt.graphiteShade(0f))
        assertEquals(5, HomeWallpaperArt.graphiteShade(1f))
        assertTrue(HomeWallpaperArt.graphiteShade(0.25f) > HomeWallpaperArt.graphiteShade(0.75f))
    }

    @Test fun carbonPaletteMatchesPreviewWeave() {
        assertEquals(0x1A1A1E, HomeWallpaperArt.carbonBase(horizontal = true))
        assertEquals(0x101013, HomeWallpaperArt.carbonBase(horizontal = false))
        assertEquals(0x26262C, HomeWallpaperArt.carbonStrip(horizontal = true))
        assertEquals(0x1E1E23, HomeWallpaperArt.carbonStrip(horizontal = false))
    }

    @Test fun starFieldIsSeededStableAndBounded() {
        val first = HomeWallpaperArt.starField(1024, 600)
        val second = HomeWallpaperArt.starField(1024, 600)
        assertEquals(first, second)
        assertEquals(170, first.size)
        for (star in first) {
            assertTrue(star.x in 0 until 1024)
            assertTrue(star.y in 0..(600 * 0.55f).toInt())
            assertTrue(star.alpha in 60..190)
            assertTrue(star.size in 1..2)
        }
    }
}
