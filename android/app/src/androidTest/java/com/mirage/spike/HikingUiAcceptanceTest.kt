package com.mirage.spike

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.mirage.spike.engine.*
import com.mirage.spike.hiking.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Real Compose sheet, deterministic provider geometry; no fabricated live-service claim. */
class HikingUiAcceptanceTest {
    @get:Rule val compose=createComposeRule()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val a=LatLng(33.5,-112.0)
    private val trail=HikingTrail("fixture","Fixture Canyon",listOf(a,Geo.offset(a,2000.0,0.0)),"https://www.openstreetmap.org/way/1",true)
    private val parking=TrailParking("fixture","Canyon parking",Geo.offset(a,-100.0,0.0))
    private var confirmed: HikingTrip?=null
    private val source=object:TrailSource {
        override suspend fun search(name:String,near:LatLng)=listOf(trail)
        override suspend fun parking(trail:HikingTrail)=listOf(parking)
    }
    private val planner=HikingPlanner(scope,source,{_,_->emptyList()},{s->
        val p=listOf(s.origin,Geo.gcInterp(s.origin,s.destination,0.5),s.destination)
        val d=TrailGeometry.length(p);RouteResult(p,d,d/1.1)
    })
    @Before fun setup() {
        compose.runOnIdle{planner.open(a)}
        compose.setContent{MaterialTheme{HikingSheet(planner,{Geo.offset(a,-1000.0,0.0)},false){confirmed=it}}}
    }
    @After fun close(){scope.cancel()}
    private fun select() {
        compose.onNodeWithTag("hikeQuery").performTextInput("Fixture Canyon")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag("searchTrails").performScrollTo().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("chooseTrail-fixture").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("chooseTrail-fixture").performScrollTo().performClick()
        compose.onNodeWithTag("trailLength").performScrollTo().assertTextContains("1.24 mi",substring=true)
        compose.onNodeWithTag("chooseParking-fixture").performScrollTo().performClick()
    }
    @Test fun namedTrailShowsLengthAsksMilesAndConfirmsWholeTrip() {
        select()
        compose.onNodeWithTag("hikeMiles").performScrollTo().performTextReplacement("1.0")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag("prepareHike").performScrollTo().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("confirmHike").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("hikeSummary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("On-trail hike: 1.00 mi").assertExists()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("mkdir -p /sdcard/Download/mirage-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/mirage-acceptance/hiking-preview.png")
        assertNull(confirmed)
        compose.onNodeWithTag("confirmHike").performScrollTo().performClick()
        compose.runOnIdle {
            val trip=confirmed!!
            assertEquals(4,trip.stops.size)
            assertEquals(METERS_PER_MILE,trip.trailMeters,0.05)
            assertEquals(TravelMode.DRIVE,trip.stops.first().mode)
            assertEquals(ArrivalActivity.PARKED,trip.stops.last().arrivalActivity)
            assertEquals(parking.point,trip.stops.last().point)
        }
    }
    @Test fun invalidMileagePreventsConfirmationAndCancelMakesNoTrip() {
        select()
        compose.onNodeWithTag("hikeMiles").performScrollTo().performTextReplacement("999")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag("prepareHike").performScrollTo().performClick()
        compose.onNodeWithTag("hikeError").assertTextContains("Choose no more",substring=true)
        compose.onNodeWithTag("confirmHike").assertDoesNotExist()
        compose.onNodeWithTag("cancelHike").performScrollTo().performClick()
        compose.runOnIdle{assertNull(confirmed);assertNull(planner.state)}
    }
}
