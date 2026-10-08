# Mirage 0.15.0: named hiking trips

## User flow

Tap **Hike** on the idle map or **Add stop → Hiking trail** during a simulation. Enter the trail name; optionally find and select a different city or park. Search covers 50 km (31 miles) around that location. Choose a result to see its mapped length in miles and whether it is a loop or an open path. Named path fragments are explicitly labelled as potentially part of a longer trail.

Choose a mapped parking area or tap the trail map to place a parking pin. Enter total on-trail miles, including the return. Use the full-loop/full-out-and-back button for the complete route. The default parking stay is two minutes and can be set from zero to sixty. Preview shows separate drive, parking, on-trail and parking-connection distances. Confirm adds the trip to the itinerary; Start begins an idle draft. During a running simulation, the existing continuation review asks when to add it.

The trip drives to the routed parking endpoint, stays parked, walks to the trailhead, follows the selected trail and walks back to the car. Driving and the parking connection are not included in requested hiking mileage. Full loops follow the loop once; shorter distances walk halfway out and retrace the same path. Open trails have a maximum of twice their mapped length. Hiking pace is 2.5 mph. The final position stays at the car until stopped or continued.

## Data and implementation

OpenStreetMap data is queried through `https://maps.mail.ru/osm/tools/overpass/api/interpreter`. Provider availability is external. Search is explicit, bounded, serialized and cached for fifteen minutes; it is not sent on keystrokes. OSM attribution and source links appear in the planner and during on-trail playback. Area lookup and drive/walk connections use Mirage's existing Google Places/Directions configuration.

Mapped hiking/foot relations and named path/footway/track/steps ways are supported. Only continuous geometry is accepted: disconnected, branching, truncated and malformed results are rejected. Nested relations are not supported. This does not promise every named trail worldwide or official posted distance. Lengths are calculated from mapped geometry; access and parking require user review.

Every prepared leg stores exact geometry in the existing saved itinerary format. Save, reload and backup retain trail paths. Parked and trailhead arrival states do not trigger indoor walking or outdoor wandering. Cancellation and stale network callbacks cannot alter a live trip. Recovery uses route progress to distinguish outbound and return positions on retraced paths.

## Validation scope

Added JVM tests cover distance trimming, full loops, geometry joins, parking orientation, route connections, save/reload, draft preservation, cancellation, invalid miles, stationary parking, parser failures and return-path recovery. Added emulator journeys exercise name → length → parking → mileage → preview → confirmation, invalid mileage/cancel, and live entry/cancel. Provider reachability is recorded separately from deterministic tests. Final results and APK identity will be recorded after CI completes.

## Sources

- https://wiki.openstreetmap.org/wiki/Overpass_API (public instance terms)
- https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL (query and geometry syntax)
- https://www.openstreetmap.org/copyright (data attribution/licensing)
