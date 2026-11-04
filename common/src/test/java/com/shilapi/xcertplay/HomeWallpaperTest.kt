package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** src18: 壁纸纯函数的回归测试（数值逐一对应 render_wallpapers.py 的 draw_bg）。 */
class HomeWallpaperTest {

    @Test fun normalizeFallsBackToBlackForUnknownStyles() {
        assertEquals(HomeWallpaper.BLACK, HomeWallpaper.normalize(-1))
        assertEquals(HomeWallpaper.BLACK, HomeWallpaper.normalize(9))
        assertEquals(HomeWallpaper.BLACK, HomeWallpaper.normalize(Int.MAX_VALUE))
        assertEquals(9, HomeWallpaper.ALL.size)
        for (style in HomeWallpaper.ALL) {
            assertEquals(style, HomeWallpaper.normalize(style))
        }
    }

    @Test fun imageStylesMapToAssetsAndFocus() {
        assertEquals("wallpapers/anime_sakura.webp", HomeWallpaper.assetPath(HomeWallpaper.NIGHT_SAKURA))
        assertEquals("wallpapers/anime_stars.webp", HomeWallpaper.assetPath(HomeWallpaper.UNDER_THE_STARS))
        assertEquals("wallpapers/anime_cyber.webp", HomeWallpaper.assetPath(HomeWallpaper.CYBER_CITY))
        assertEquals("wallpapers/anime_sea.webp", HomeWallpaper.assetPath(HomeWallpaper.DUSK_SEASIDE))
        assertEquals(null, HomeWallpaper.assetPath(HomeWallpaper.MIDNIGHT))
        assertEquals(0.38f, HomeWallpaper.focusX(HomeWallpaper.NIGHT_SAKURA), 0.001f)
        assertEquals(0.42f, HomeWallpaper.focusX(HomeWallpaper.UNDER_THE_STARS), 0.001f)
        assertEquals(0.58f, HomeWallpaper.focusX(HomeWallpaper.CYBER_CITY), 0.001f)
        assertEquals(0.55f, HomeWallpaper.focusX(HomeWallpaper.DUSK_SEASIDE), 0.001f)
    }

    @Test fun centerCropUsesFocusOnTheWideAxis() {
        // 插画原图 2352x1008 进 1024x600：源更宽 → 裁宽，窗口宽 1720、余量 632。
        val sakura = HomeWallpaperArt.centerCrop(2352, 1008, 1024, 600, 0.38f)
        assertEquals(HomeWallpaperArt.Crop(240, 0, 1720, 1008), sakura)
        val cyber = HomeWallpaperArt.centerCrop(2352, 1008, 1024, 600, 0.58f)
        assertEquals(HomeWallpaperArt.Crop(367, 0, 1720, 1008), cyber)
        assertEquals(0, HomeWallpaperArt.centerCrop(2352, 1008, 1024, 600, 0f).left)
        assertEquals(632, HomeWallpaperArt.centerCrop(2352, 1008, 1024, 600, 1f).left)
        // 焦点越界夹紧到两端。
        assertEquals(632, HomeWallpaperArt.centerCrop(2352, 1008, 1024, 600, 2f).left)
    }

    @Test fun centerCropCentersVerticallyWhenSourceIsTaller() {
        val crop = HomeWallpaperArt.centerCrop(1008, 2352, 1024, 600, 0.5f)
        assertEquals(HomeWallpaperArt.Crop(0, 881, 1008, 591), crop)
    }

    @Test fun vignetteAlphaFallsToZeroAtSixtyTwoFivePercent() {
        assertEquals(140, HomeWallpaperArt.vignetteAlpha(0, 1024))
        assertEquals(70, HomeWallpaperArt.vignetteAlpha(320, 1024))
        assertEquals(118, HomeWallpaperArt.vignetteAlpha(100, 1024))
        assertEquals(0, HomeWallpaperArt.vignetteAlpha(640, 1024))
        assertEquals(0, HomeWallpaperArt.vignetteAlpha(1023, 1024))
        assertEquals(26, HomeWallpaperArt.IMAGE_DARKEN_ALPHA)
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
