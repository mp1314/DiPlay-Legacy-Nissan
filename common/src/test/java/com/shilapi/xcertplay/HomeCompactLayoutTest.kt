package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HomeCompactLayoutTest {
    @Config(qualifiers = "en-w2667dp-h1333dp")
    @Test fun fullSizeMultiWindowKeepsTheFullHome() {
        assertFalse(homeIsCompact(multiWindow = true))
    }

    @Config(qualifiers = "en-w1333dp-h400dp")
    @Test fun shortWindowUsesTheCompactHome() {
        assertTrue(homeIsCompact(multiWindow = true))
    }

    @Config(qualifiers = "en-w500dp-h800dp")
    @Test fun narrowWindowUsesTheCompactHome() {
        assertTrue(homeIsCompact(multiWindow = false))
    }

    private fun homeIsCompact(multiWindow: Boolean): Boolean {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        shadowOf(activity).setInMultiWindowMode(multiWindow)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        // The full Nissan home brands the screen with the logo; the compact
        // home drops it. (The old hero line no longer exists in the UI.)
        val logoDescription = activity.getString(R.string.carplay_icon)
        return descendants(activity.window.decorView).filterIsInstance<ImageView>()
            .none { it.contentDescription == logoDescription }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
