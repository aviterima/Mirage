package com.mirage.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The car help observes state; changing connections never opens settings by itself. */
class AndroidAutoHelpUiAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wifiStatesStayInformationalAndSettingsRequiresAnExplicitTap() {
        var wifiEnabled by mutableStateOf<Boolean?>(true)
        var settingsOpened = 0
        compose.setContent {
            MirageTheme {
                AndroidAutoHelpCard(wifiEnabled = wifiEnabled, onOpenWifiSettings = { settingsOpened++ })
            }
        }
        compose.onNodeWithText("Android Auto & Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi is on").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, settingsOpened); wifiEnabled = false }
        compose.onNodeWithText("Wi-Fi is off").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, settingsOpened); wifiEnabled = null }
        compose.onNodeWithText("Wi-Fi status unavailable").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, settingsOpened) }
        compose.onNodeWithTag("carWifiSettings").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened); wifiEnabled = true }
        compose.onNodeWithText("Wi-Fi is on").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, settingsOpened) }
    }
}
