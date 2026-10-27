import Foundation

public struct Coordinate: Equatable, Sendable {
    public var lat: Double
    public var lng: Double
    public init(lat: Double, lng: Double) { self.lat = lat; self.lng = lng }
    public var isValid: Bool {
        lat.isFinite && lng.isFinite && (-90...90).contains(lat) && (-180...180).contains(lng)
    }
}

public enum TravelMode: String, CaseIterable, Sendable {
    case drive = "DRIVE", bike = "BIKE", walk = "WALK", transit = "TRANSIT", fly = "FLY"
}
public enum Realism: String, CaseIterable, Sendable {
    case constant = "CONSTANT", realistic = "REALISTIC", busy = "BUSY"
}
public enum ScenarioKind: String, Sendable {
    case place = "SNAP", route = "ROUTE", itinerary = "ITINERARY"
}
public enum ArrivalActivity: String, Sendable {
    case building = "BUILDING", table = "TABLE", office = "OFFICE", conference = "CONFERENCE"
    case outdoor = "OUTDOOR", parked = "PARKED", trailhead = "TRAILHEAD"
}

public struct RouteSegment: Equatable, Sendable {
    public var points: [Coordinate]
    public var distance: Double
    public var seconds: Double
    public var instruction: String
    public var maneuver: String
    public init(points: [Coordinate], distance: Double, seconds: Double,
                instruction: String = "", maneuver: String = "") {
        self.points = points; self.distance = distance; self.seconds = seconds
        self.instruction = instruction; self.maneuver = maneuver
    }
}

/// Android's exact-geometry archive. It intentionally does not archive live transit schedules.
public struct ArchivedRoute: Equatable, Sendable {
    public var points: [Coordinate]
    public var distance: Double
    public var seconds: Double
    public var fetched: Int64
    public var segments: [RouteSegment]
    public init(points: [Coordinate], distance: Double, seconds: Double,
                fetched: Int64 = 0, segments: [RouteSegment] = []) {
        self.points = points; self.distance = distance; self.seconds = seconds
        self.fetched = fetched; self.segments = segments
    }
}

public struct SavedStop: Equatable, Sendable {
    public var name: String
    public var point: Coordinate
    public var dwellMinutes: Int
    public var mode: TravelMode
    public var avgMph: Double
    public var address: String
    public var placeId: String
    public var routingRealism: Realism?
    public var routingTransitPref: String?
    public var ownRoutingPreferences: Bool
    public var frozenRoute: ArchivedRoute?
    public var arriveByMillis: Int64?
    public var arrivalActivity: ArrivalActivity
    public var entrance: Coordinate?
    public var stayUntilLeave: Bool

    public init(name: String, point: Coordinate, dwellMinutes: Int = 30,
                mode: TravelMode = .drive, avgMph: Double = 45, address: String = "",
                placeId: String = "", routingRealism: Realism? = nil,
                routingTransitPref: String? = nil, ownRoutingPreferences: Bool = false,
                frozenRoute: ArchivedRoute? = nil, arriveByMillis: Int64? = nil,
                arrivalActivity: ArrivalActivity = .building, entrance: Coordinate? = nil,
                stayUntilLeave: Bool = false) {
        self.name = name; self.point = point; self.dwellMinutes = dwellMinutes
        self.mode = mode; self.avgMph = avgMph; self.address = address; self.placeId = placeId
        self.routingRealism = routingRealism; self.routingTransitPref = routingTransitPref
        self.ownRoutingPreferences = ownRoutingPreferences; self.frozenRoute = frozenRoute
        self.arriveByMillis = arriveByMillis; self.arrivalActivity = arrivalActivity
        self.entrance = entrance; self.stayUntilLeave = stayUntilLeave
    }
}

/// A named template, not a live playback checkpoint. Millisecond dates use Android's Unix epoch.
public struct SavedScenario: Equatable, Sendable {
    public var id: String
    public var name: String
    public var kind: ScenarioKind
    public var createdAt: Int64
    public var startIsReal: Bool
    public var start: Coordinate?
    public var startName: String
    public var dest: Coordinate?
    public var destName: String
    public var travelMode: TravelMode
    public var speeds: [TravelMode: Double]
    public var realism: Realism
    public var transitPref: String?
    public var stops: [SavedStop]
    public var destAddress: String
    public var destPlaceId: String
    public var favorite: Bool
    public var lastUsedAt: Int64
    public var aliases: [String]
    public var frozenRoute: ArchivedRoute?
    public var defaultStayMinutes: Int
    public var departureMillis: Int64?
    public var arrivalActivity: ArrivalActivity
    public var entrance: Coordinate?
    public var stayUntilLeave: Bool

    public init(id: String, name: String, kind: ScenarioKind = .route, createdAt: Int64 = 0,
                startIsReal: Bool = false, start: Coordinate? = nil, startName: String = "",
                dest: Coordinate? = nil, destName: String = "", travelMode: TravelMode = .drive,
                speeds: [TravelMode: Double] = [:], realism: Realism = .realistic,
                transitPref: String? = nil, stops: [SavedStop] = [], destAddress: String = "",
                destPlaceId: String = "", favorite: Bool = false, lastUsedAt: Int64 = 0,
                aliases: [String] = [], frozenRoute: ArchivedRoute? = nil,
                defaultStayMinutes: Int = 0, departureMillis: Int64? = nil,
                arrivalActivity: ArrivalActivity = .building, entrance: Coordinate? = nil,
                stayUntilLeave: Bool = false) {
        self.id = id; self.name = name; self.kind = kind; self.createdAt = createdAt
        self.startIsReal = startIsReal; self.start = start; self.startName = startName
        self.dest = dest; self.destName = destName; self.travelMode = travelMode
        self.speeds = speeds; self.realism = realism; self.transitPref = transitPref
        self.stops = stops; self.destAddress = destAddress; self.destPlaceId = destPlaceId
        self.favorite = favorite; self.lastUsedAt = lastUsedAt; self.aliases = aliases
        self.frozenRoute = frozenRoute; self.defaultStayMinutes = defaultStayMinutes
        self.departureMillis = departureMillis; self.arrivalActivity = arrivalActivity
        self.entrance = entrance; self.stayUntilLeave = stayUntilLeave
    }
}
