package com.mirage.spike.hiking

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.mirage.spike.engine.LatLng
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HikingSheet(planner: HikingPlanner, origin: () -> LatLng?, active: Boolean, currentLocation: HikingLocation? = null, onConfirm: (HikingTrip)->Unit) {
    val d=planner.state ?: return
    val uri=LocalUriHandler.current
    ModalBottomSheet(onDismissRequest=planner::close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=720.dp).imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp).testTag("hikingEditor"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Plan a hike",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("Drive → park → hike → return to parking",style=MaterialTheme.typography.bodyMedium)
            Text("Search within 31 miles of ${d.areaName}",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(d.areaQuery,planner::areaQuery,label={Text("Different city or park (optional)")},modifier=Modifier.fillMaxWidth().testTag("hikeArea"),singleLine=true,enabled=!d.busy)
            TextButton(onClick=planner::findArea,enabled=!d.busy && d.areaQuery.isNotBlank()){Text("Find area")}
            if(d.areas.isNotEmpty())Text("Choose the city or park to search:")
            d.areas.forEach {a->OutlinedButton(onClick={planner.chooseArea(a)},modifier=Modifier.fillMaxWidth()) {Text("${a.name} · ${a.address}")}}
            OutlinedTextField(d.query,planner::query,label={Text("Trail name (optional)")},placeholder={Text("e.g. Echo Canyon Trail")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("hikeQuery"),enabled=!d.busy)
            Button(onClick=planner::search,enabled=!d.busy,modifier=Modifier.testTag("searchTrails")){Text(if(d.query.isBlank()) "Browse hiking trails" else "Find hiking trails")}
            Text("Leave the trail name blank to browse. Showing up to 20 mapped trails or sections; use a name or a nearby park to refine results.",style=MaterialTheme.typography.bodySmall)
            if(currentLocation==null)Text("Current location unavailable. Enable Location to see distances.",style=MaterialTheme.typography.bodySmall)
            if(d.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            d.error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("hikeError"))}
            if(d.searched && d.trails.isEmpty() && !d.busy)Text("No continuous mapped trail found. Try another name or area. Missing or branching paths are not estimated.")
            if(d.selected==null)d.trails.forEach {trail->
                OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
                    Text(trail.name,fontWeight=FontWeight.Bold)
                    currentLocation?.let {Text(it.distanceLabel(trail),modifier=Modifier.testTag("trailDistance-${trail.id}"))}
                    Text("${milesText(trail.meters)} · ${if(trail.loop) "loop" else "one way; ${milesText(trail.maxHikeMeters)} out and back"}")
                    if(trail.mappedSection)Text("Mapped named path; may be only part of a longer trail.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={planner.select(trail)},enabled=!d.busy,modifier=Modifier.testTag("chooseTrail-${trail.id}")){Text("Choose trail")}
                }}
            }
            d.selected?.let {trail->
                HorizontalDivider()
                Text(trail.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                currentLocation?.let {Text(it.distanceLabel(trail),modifier=Modifier.testTag("selectedTrailDistance"))}
                Text("Mapped length: ${milesText(trail.meters)} ${if(trail.loop) "(full loop)" else "(one way)"}",modifier=Modifier.testTag("trailLength"))
                if(trail.mappedSection)Text("This is the continuous named path available in the map data; it may not represent the entire advertised trail.",style=MaterialTheme.typography.bodySmall)
                Text("Choose parking",style=MaterialTheme.typography.titleMedium)
                Text("Select a mapped parking area, or tap the map to place your own parking pin.",style=MaterialTheme.typography.bodySmall)
                if(d.parking.isEmpty())Text("No parking area is mapped nearby. Place a parking pin on the map.")
                d.parking.forEach {p->OutlinedButton(onClick={planner.chooseParking(p)},enabled=!d.busy,modifier=Modifier.fillMaxWidth().testTag("chooseParking-${p.id}")) {
                    Text((if(d.selectedParking?.id==p.id) "✓ " else "")+p.name+" · "+milesText(trail.points.minOf {com.mirage.spike.engine.Geo.haversine(p.point,it)})+" from trail")
                }}
                TrailMap(trail,d.selectedParking,!d.busy,planner::chooseParking)
                d.selectedParking?.let {Text("Parking: ${it.name}",modifier=Modifier.testTag("selectedHikeParking"))}
                Text("How many miles should be simulated?",style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(d.miles,planner::miles,label={Text("Miles on trail, including return")},singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!d.busy,modifier=Modifier.fillMaxWidth().testTag("hikeMiles"))
                Text(if(d.wholeTrail) {if(trail.loop)"Full loop selected." else "Full out-and-back selected."} else "Turn around halfway through your chosen mileage and return on the same trail.",style=MaterialTheme.typography.bodySmall)
                Text("Maximum: ${milesText(trail.maxHikeMeters)}. Driving and the walk from parking are additional.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=planner::wholeTrail,enabled=!d.busy){Text(if(trail.loop) "Use full loop" else "Use full out-and-back")}
                OutlinedTextField(d.parkingMinutes,planner::parkingMinutes,label={Text("Minutes parked before hiking")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,enabled=!d.busy,modifier=Modifier.fillMaxWidth().testTag("hikeParkingMinutes"))
                if(d.trip==null)Button(onClick={planner.prepare(origin())},enabled=!d.busy && d.selectedParking!=null,modifier=Modifier.fillMaxWidth().testTag("prepareHike")){Text("Preview drive and hike")}
                d.trip?.let {trip->
                    Card(Modifier.fillMaxWidth().testTag("hikeSummary")) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Your hiking trip",fontWeight=FontWeight.Bold)
                        Text("Drive: ${milesText(trip.driveMeters)}")
                        Text("Parking: ${d.parkingMinutes} min")
                        Text("On-trail hike: ${milesText(trip.trailMeters)}")
                        Text("Parking connection, both ways: ${milesText(trip.walkMeters)}")
                        Text("Hiking pace: 2.5 mph · ends back at parking")
                    }}
                    Button(onClick={planner.takePrepared()?.let(onConfirm)},modifier=Modifier.fillMaxWidth().testTag("confirmHike")) {Text(if(active) "Review adding hike to current trip" else "Add hike to itinerary")}
                }
                TextButton(onClick={uri.openUri(trail.sourceUrl)}){Text("View source trail")}
            }
            Text("Lengths are estimates from mapped geometry. Parking and trail access must be reviewed.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={uri.openUri("https://www.openstreetmap.org/copyright")}){Text("Trail data © OpenStreetMap contributors")}
            TextButton(onClick=planner::close,modifier=Modifier.testTag("cancelHike")){Text("Cancel")}
        }
    }
}

@Composable
private fun TrailMap(trail: HikingTrail, parking: TrailParking?, enabled: Boolean, onParking: (TrailParking)->Unit) {
    fun LatLng.mapPoint()=com.google.android.gms.maps.model.LatLng(lat,lng)
    val camera=rememberCameraPositionState {position=CameraPosition.fromLatLngZoom(trail.points.first().mapPoint(),13f)}
    var loaded by remember {mutableStateOf(false)}
    LaunchedEffect(loaded,trail.id) {
        if(loaded)runCatching {val bounds=LatLngBounds.builder();trail.points.forEach {bounds.include(it.mapPoint())};camera.move(CameraUpdateFactory.newLatLngBounds(bounds.build(),36))}
    }
    GoogleMap(modifier=Modifier.fillMaxWidth().height(220.dp).testTag("hikeMap"),cameraPositionState=camera,
        uiSettings=MapUiSettings(mapToolbarEnabled=false),onMapLoaded={loaded=true},onMapClick={p->if(enabled)onParking(TrailParking("pin","Selected parking pin",LatLng(p.latitude,p.longitude)))}) {
        Polyline(points=trail.points.map {it.mapPoint()},color=androidx.compose.ui.graphics.Color(0xFF15803D),width=7f)
        parking?.let {Marker(state=MarkerState(position=it.point.mapPoint()),title=it.name)}
    }
}
