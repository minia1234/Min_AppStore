package dev.indi.appstore

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreThemeTest {
    @Test fun bothThemesKeepTextReadableOnTheirSurfaces() {
        listOf(StoreLightColors, StoreDarkColors).forEach { colors ->
            listOf(colors.primary to colors.onPrimary, colors.primaryContainer to colors.onPrimaryContainer,
                colors.background to colors.onBackground, colors.surface to colors.onSurface,
                colors.surface to colors.onSurfaceVariant).forEach { (background, foreground) ->
                val luminances = listOf(background.luminance(), foreground.luminance()).sorted()
                assertTrue("Normal text must meet 4.5:1 contrast", (luminances[1] + 0.05f) / (luminances[0] + 0.05f) >= 4.5f)
            }
        }
    }
}
