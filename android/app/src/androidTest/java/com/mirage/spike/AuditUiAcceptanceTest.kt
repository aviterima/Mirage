package com.mirage.spike

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.mirage.spike.audit.AuditTrail
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.LiveSession
import com.mirage.spike.engine.PlaybackSource
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.Signal
import com.mirage.spike.engine.TravelMode
import com.mirage.spike.store.PrefsScenarioStore
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** User-visible audit controls and privacy checks against the same report exported by the UI. */
class AuditUiAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val device get() = UiDevice.getInstance(inst)
    private val privateName = "Private Home Cypress 827"
    private val privateAddress = "827 Private Cypress Lane"
    private val privateAlias = "private-cypress-alias-827"

    @Before fun launch() {
        context.stopService(Intent(context, MockLocationService::class.java))
        context.getSharedPreferences("mirage_recovery", Context.MODE_PRIVATE).edit().clear().commit()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.paused = false; PlaybackSource.signal = Signal.GOOD
        val home = SavedScenario("audit-private-home", privateName, "SNAP", 1L, false,
            null, "", LatLng(33.5123456, -112.0123456), privateName, TravelMode.DRIVE,
            emptyMap(), Realism.REALISTIC, null, emptyList(), privateAddress,
            aliases = listOf(privateAlias))
        PrefsScenarioStore(context).save(listOf(home))
        listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS").forEach {
            device.executeShellCommand("pm grant com.mirage.app android.permission.$it")
        }
        device.executeShellCommand("am force-stop com.google.android.apps.nexuslauncher")
        AuditTrail.initialize(context)
        runBlocking { AuditTrail.setEnabled(true); AuditTrail.clear() }
        activity = ActivityScenario.launch(MainActivity::class.java)
        awaitPlanner()
    }

    @After fun close() {
        if (::activity.isInitialized) activity.close()
        device.executeShellCommand("settings put system font_scale 1.0")
        context.stopService(Intent(context, MockLocationService::class.java))
        context.getSharedPreferences("mirage_recovery", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { AuditTrail.setEnabled(true); AuditTrail.clear() }
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
    }

    private fun awaitPlanner() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Saved plans").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openFromSetup() {
        compose.onNodeWithContentDescription("Setup").performScrollTo().performClick()
        compose.onNodeWithTag("setupDiagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("auditDiagnostics").assertIsDisplayed()
        compose.waitUntil(5_000) { AuditTrail.status.value.ready }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle(); device.waitForIdle()
        assertEquals("No system dialog may cover the app", "com.mirage.app", device.currentPackageName)
        device.executeShellCommand("mkdir -p /sdcard/Download/mirage-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/mirage-acceptance/$name.png")
    }

    private fun markProblem() {
        compose.onNodeWithTag("auditMarkProblem").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Problem marker:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("auditMessage").performScrollTo().assertTextContains("Problem marker:", substring = true)
    }

    @Test fun markerPauseClearAndReopenPreserveUserControl() {
        compose.onNodeWithText("Settings", substring = false).performClick()
        compose.onNodeWithTag("plannerDiagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("auditEnabled").assertIsOn()
        markProblem()
        val reportWithMarker = runBlocking { AuditTrail.exportReport() }
        val events = JSONObject(reportWithMarker).getJSONArray("events")
        val markerEvent = (0 until events.length()).map(events::getJSONObject)
            .single { it.getString("action") == "PROBLEM_MARKER" }
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        assertTrue("Report includes a local problem marker", uuid.matches(markerEvent.getString("marker")))
        compose.onNodeWithTag("auditMessage").assertTextContains(markerEvent.getString("marker"), substring = true)
        screenshot("audit-problem-marker")

        compose.onNodeWithTag("auditEnabled").performScrollTo().performClick()
        compose.waitUntil(5_000) { !AuditTrail.status.value.enabled }
        compose.onNodeWithTag("auditMarkProblem").performScrollTo().assertIsNotEnabled()
        val pausedCount = AuditTrail.status.value.eventCount
        compose.onNodeWithTag("auditDone").performClick()
        activity.recreate()
        awaitPlanner(); openFromSetup()
        compose.onNodeWithTag("auditEnabled").assertIsOff()
        assertEquals("Reopening while paused does not record new events", pausedCount, AuditTrail.status.value.eventCount)
        compose.onNodeWithTag("auditClear").performScrollTo().performClick()
        compose.onNodeWithTag("auditConfirmClear").performClick()
        compose.waitUntil(5_000) { AuditTrail.status.value.eventCount == 0 }
        assertEquals(0, JSONObject(runBlocking { AuditTrail.exportReport() }).getJSONArray("events").length())
        compose.onNodeWithTag("auditDone").performClick()
        openFromSetup()
        compose.onNodeWithTag("auditEventCount").performScrollTo().assertTextEquals("0 recorded events")
        assertEquals("Clearing history preserves saved places", privateName, PrefsScenarioStore(context).load().single().name)
        screenshot("audit-paused-empty-history")
    }

    @Test fun reportOmitsPrivatePlannerInputs() {
        compose.onNodeWithTag("plannerSavedDestination").performScrollTo().performClick()
        compose.onNodeWithTag("plannerSavedPlaceSearch").performTextInput(privateAlias)
        compose.onNodeWithTag("plannerSavedPlace-audit-private-home").performClick()
        compose.onNodeWithTag("plannerDestinationInput").assertTextContains(privateName)
        compose.onNodeWithTag("plannerDestinationInput").performTextReplacement("private typed query 827")
        compose.onNodeWithContentDescription("Setup").performScrollTo().performClick()
        // Only type into the editable field; do not save/test an API key or contact a provider.
        val keyField = compose.onAllNodesWithText("Your Google Maps API key").fetchSemanticsNodes()
        if (keyField.isNotEmpty()) {
            compose.onNodeWithText("Your Google Maps API key").performScrollTo()
                .performTextReplacement("private-api-key-input-827")
        }
        compose.onNodeWithTag("setupDiagnostics").performScrollTo().performClick()
        markProblem()
        val report = runBlocking { AuditTrail.exportReport() }
        val events = JSONObject(report).getJSONArray("events")
        val selections = (0 until events.length()).map(events::getJSONObject)
            .filter { it.getString("action") == "SAVED_SELECT" }
        val requested = selections.single { it.getString("outcome") == "REQUESTED" }
        val succeeded = selections.single { it.getString("outcome") == "SUCCEEDED" }
        assertEquals(requested.getLong("operation"), succeeded.getLong("operation"))
        assertEquals("END", succeeded.getJSONObject("state").getString("field"))
        assertTrue(succeeded.getJSONObject("state").getBoolean("hasDestination"))
        assertFalse(succeeded.getJSONObject("state").getBoolean("running"))
        listOf(privateName, privateAddress, privateAlias, "audit-private-home", "private typed query 827",
            "private-api-key-input-827", "33.5123456", "-112.0123456").forEach { privateValue ->
            assertFalse("Report must not contain private fixture data", report.contains(privateValue))
        }
        assertFalse("Diagnostics do not start simulation", MockState.status.value.running)
        screenshot("audit-private-planner-report")
    }

    @Test fun diagnosticsRemainReachableWithLargeTextAndKeyboard() {
        device.executeShellCommand("settings put system font_scale 1.3")
        activity.recreate(); awaitPlanner()
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().performTextInput("keyboard fixture")
        compose.onNodeWithTag("plannerDestinationInput").performClick()
        compose.waitUntil(15_000) {
            var keyboardVisible = false
            activity.onActivity {
                keyboardVisible = it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
            }
            keyboardVisible
        }
        compose.onNodeWithContentDescription("Setup").performScrollTo().performClick()
        compose.onNodeWithTag("setupDiagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("auditEnabled").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auditMarkProblem").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auditExport").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("auditClear").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auditDone").assertIsDisplayed()
        screenshot("audit-large-text-controls")
    }

    @Test fun nativeDocumentExportWritesTheRedactedReportToDownloads() {
        compose.onNodeWithTag("plannerSavedDestination").performScrollTo().performClick()
        compose.onNodeWithTag("plannerSavedPlace-audit-private-home").performClick()
        openFromSetup(); markProblem()
        val markedEvents = JSONObject(runBlocking { AuditTrail.exportReport() }).getJSONArray("events")
        val marker = (0 until markedEvents.length()).map(markedEvents::getJSONObject)
            .single { it.getString("action") == "PROBLEM_MARKER" }.getString("marker")
        val filename = "audit-native-export-${System.nanoTime()}.json"
        val path = "/sdcard/Download/$filename"
        try {
            compose.onNodeWithTag("auditExport").performScrollTo().performClick()
            // Exercise Android's real CreateDocument picker on the API 34 CI emulator.
            // No result interception or replacement report writer is used.
            val roots = device.findObject(UiSelector().description("Show roots"))
            assertTrue("Android document chooser must open", roots.waitForExists(15_000))
            roots.click()
            val downloads = device.findObject(UiSelector().text("Downloads"))
            assertTrue("Downloads must be available in the document chooser", downloads.waitForExists(5_000))
            downloads.click()
            val name = device.findObject(UiSelector().className("android.widget.EditText"))
            assertTrue("CreateDocument must offer a filename", name.waitForExists(5_000))
            name.setText(filename)
            val save = device.findObject(UiSelector().textMatches("(?i)save"))
            assertTrue("CreateDocument must offer Save", save.waitForExists(5_000))
            save.click()
            // Return to Compose synchronization after the external picker. A native accessibility
            // wait cannot advance Compose's test clock or find a message below the scroll viewport.
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Report saved.", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("Couldn't save the diagnostic report.", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("auditMessage").performScrollTo().assertTextContains("Report saved.", substring = true)

            // Read the bytes created by the native document provider, independently of AuditTrail.
            val report = device.executeShellCommand("cat $path")
            assertTrue("Export must be a bounded, nonempty file", report.isNotBlank() && report.toByteArray().size <= 512 * 1024)
            val exported = JSONObject(report)
            assertEquals(1, exported.getInt("schema"))
            assertEquals(BuildConfig.VERSION_NAME, exported.getString("appVersion"))
            val events = exported.getJSONArray("events")
            assertTrue((0 until events.length()).map(events::getJSONObject).any {
                it.getString("action") == "PROBLEM_MARKER" && it.getString("marker") == marker
            })
            listOf(privateName, privateAddress, privateAlias, "audit-private-home", "33.5123456", "-112.0123456").forEach {
                assertFalse("The written report must omit private fixture data", report.contains(it))
            }
            assertFalse("Export must not start simulation", MockState.status.value.running)
            screenshot("audit-native-export-saved")
            device.executeShellCommand("cp $path /sdcard/Download/mirage-acceptance/audit-exported-report.json")
        } catch (failure: Throwable) {
            // Capture the native picker/app state without replacing the original failure.
            runCatching {
                device.executeShellCommand("mkdir -p /sdcard/Download/mirage-acceptance")
                device.executeShellCommand("screencap -p /sdcard/Download/mirage-acceptance/audit-native-export-failure.png")
                val hierarchy = ByteArrayOutputStream()
                device.dumpWindowHierarchy(hierarchy)
                // Instrumentation can write its own external-files directory; the workflow pulls
                // this diagnostic via a shell copy only after it has been fully closed.
                val hierarchyFile = File(context.getExternalFilesDir(null), "audit-native-export-failure.xml")
                hierarchyFile.writeBytes(hierarchy.toByteArray())
                device.executeShellCommand("cp ${hierarchyFile.absolutePath} /sdcard/Download/mirage-acceptance/audit-native-export-failure.xml")
                hierarchyFile.delete()
                println("AUDIT export failure: package=${device.currentPackageName}; fileBytes=" +
                    device.executeShellCommand("stat -c %s $path").trim())
            }
            throw failure
        } finally {
            // This unique test-created file is the only Downloads entry removed.
            device.executeShellCommand("rm -f $path")
        }
    }
}
