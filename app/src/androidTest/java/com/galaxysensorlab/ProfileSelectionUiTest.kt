package com.galaxysensorlab

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ProfileSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun userCanSearchSelectAndRestoreS6Edge() {
        compose.onNodeWithText("Scegli fotocamera").performScrollTo().performClick()
        compose.onNodeWithTag("camera-search").performTextReplacement("OnePlus Nord 3")
        compose.onNodeWithText("OnePlus Nord 3 5G").performClick()
        compose.onNodeWithText("OnePlus Nord 3 5G").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)[LabViewModel::class.java]
            assertEquals("darktable:OnePlus:OnePlus Nord 3 5G", model.settings.profileId)
            model.update(model.settings.copy(iso = 8000f))
        }
        compose.onNodeWithText("Scegli fotocamera").performScrollTo().performClick()
        compose.onNodeWithTag("camera-search").performTextReplacement("S6 Edge")
        compose.onNodeWithText("Samsung Galaxy S6 Edge").performClick()
        compose.onNodeWithText("Samsung Galaxy S6 Edge").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)[LabViewModel::class.java]
            assertEquals(NoiseCalibration.DEFAULT_PROFILE, model.settings.profileId)
            assertEquals(3200f, model.settings.iso, 0f)
            model.update(LabSettings())
        }
    }
}
