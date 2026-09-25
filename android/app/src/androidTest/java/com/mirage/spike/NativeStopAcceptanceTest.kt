package com.mirage.spike

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.mirage.spike.engine.*
import com.mirage.spike.store.*
import org.junit.Test
import org.junit.Assert.*

/** Native accessibility taps avoid Compose test-clock ripple thread interference. */
class NativeStopAcceptanceTest {
    @Test fun uc39_nativeTapStopsSnapOutput() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val snap = SavedScenario("native-stop-fixture", "Native Home", "SNAP", 1L, false,
            null, "", LatLng(33.50, -112.0), "Native Home", TravelMode.DRIVE,
            emptyMap(), Realism.REALISTIC, null, emptyList())
        PrefsScenarioStore(ctx).save(listOf(snap))
        device.executeShellCommand("pm grant com.mirage.app android.permission.ACCESS_COARSE_LOCATION")
        device.executeShellCommand("pm grant com.mirage.app android.permission.ACCESS_FINE_LOCATION")
        device.executeShellCommand("pm grant com.mirage.app android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("appops set com.mirage.app android:mock_location allow")
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        val activity = ActivityScenario.launch(MainActivity::class.java)
        fun tap(selector: UiSelector) {
            val item = device.findObject(selector)
            assertTrue("Control not found: " + selector, item.waitForExists(20_000))
            assertTrue(item.click())
        }
        fun awaitState(predicate: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 20_000
            while (!predicate() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
            assertTrue("State timeout: " + MockState.status.value, predicate())
        }
        try {
            tap(UiSelector().description("Saved plans"))
            tap(UiSelector().description("Load Native Home"))
            tap(UiSelector().text("Snap to “Native Home”"))
            awaitState { MockState.status.value.running }
            tap(UiSelector().text("Stop"))
            awaitState { !MockState.status.value.running && !MockState.status.value.starting }
            assertTrue(device.findObject(UiSelector().textContains("SIMULATION OFF")).waitForExists(10_000))
            device.executeShellCommand("mkdir -p /sdcard/Download/mirage-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/mirage-acceptance/uc39-native-stop.png")
            println("ACCEPTANCE native Stop: output stopped. Fresh physical GPS not asserted.")
        } finally {
            ctx.stopService(Intent(ctx, MockLocationService::class.java))
            activity.close()
            MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        }
    }
}

