import Foundation
import CoreFoundation

public enum BackupError: Error, Equatable, LocalizedError {
    case tooLarge
    case unsupportedFormat
    case invalid(String)

    public var errorDescription: String? {
        switch self {
        case .tooLarge: return "Backup exceeds 2 MiB."
        case .unsupportedFormat: return "Unsupported Mirage backup format or version."
        case .invalid(let detail): return "Invalid Mirage backup: \(detail)"
        }
    }
}

/// Android mirage-backup v1 interchange. Every item is checked before returning any of them.
/// Only the documented plan fields are accepted; credentials and pairing records have no representation.
public enum BackupCodec {
    public static let maxBytes = 2 * 1024 * 1024
    public static let maxItems = 1_000

    public static func decode(_ text: String) throws -> [SavedScenario] {
        try decode(Data(text.utf8))
    }

    public static func decode(_ data: Data) throws -> [SavedScenario] {
        guard data.count <= maxBytes else { throw BackupError.tooLarge }
        let raw: Any
        do { raw = try JSONSerialization.jsonObject(with: data) }
        catch { throw BackupError.invalid("Malformed JSON.") }
        let root = try Object(raw, path: "backup", keys: ["format", "version", "items"])
        guard try root.string("format") == "mirage-backup", try root.integer("version") == 1 else {
            throw BackupError.unsupportedFormat
        }
        let items = try root.array("items")
        guard items.count <= maxItems else { throw BackupError.invalid("Import at most 1,000 items.") }
        let decoded = try items.enumerated().map { try scenario($0.element, path: "items[\($0.offset)]") }
        try validate(decoded)
        return decoded
    }

    public static func encode(_ items: [SavedScenario]) throws -> Data {
        try validate(items)
        let root: [String: Any] = ["format": "mirage-backup", "version": 1, "items": items.map(scenarioJSON)]
        let data = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
        guard data.count <= maxBytes else { throw BackupError.tooLarge }
        return data
    }

    public static func encodeString(_ items: [SavedScenario]) throws -> String {
        String(decoding: try encode(items), as: UTF8.self)
    }

    public static func validate(_ items: [SavedScenario]) throws {
        guard items.count <= maxItems else { throw BackupError.invalid("Import at most 1,000 items.") }
        for (index, item) in items.enumerated() {
            let path = "items[\(index)]"
            try require(!item.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && item.name.utf16.count <= 160,
                        "\(path).name must contain 1–160 characters.")
            try require(item.createdAt >= 0 && item.lastUsedAt >= 0 && (item.departureMillis ?? 0) >= 0,
                        "\(path) has a negative timestamp.")
            try require((0...1440).contains(item.defaultStayMinutes), "\(path) has an invalid default stay.")
            try require(item.stops.count <= 100 && item.aliases.count <= 12 && item.aliases.allSatisfy { $0.utf16.count <= 80 },
                        "\(path) has too many stops or aliases, or an alias exceeds 80 characters.")
            try require(item.speeds.values.allSatisfy { $0.isFinite && (1...1000).contains($0) }, "\(path) has an invalid speed.")
            try require([item.start, item.dest, item.entrance].compactMap { $0 }.allSatisfy(\.isValid), "\(path) has an invalid location.")
            try require(item.kind == .itinerary ? !item.stops.isEmpty : item.dest != nil, "\(path) is missing its destination.")
            if let route = item.frozenRoute { try validate(route, path: "\(path).frozenRoute") }
            for (stopIndex, stop) in item.stops.enumerated() {
                let stopPath = "\(path).stops[\(stopIndex)]"
                try require(stop.point.isValid && (stop.entrance?.isValid ?? true), "\(stopPath) has an invalid location.")
                try require((0...1440).contains(stop.dwellMinutes) && stop.avgMph.isFinite && (1...1000).contains(stop.avgMph),
                            "\(stopPath) has an invalid stay or speed.")
                try require((stop.arriveByMillis ?? 0) >= 0, "\(stopPath) has a negative timestamp.")
                if let route = stop.frozenRoute { try validate(route, path: "\(stopPath).frozenRoute") }
            }
        }
    }

    private static func validate(_ route: ArchivedRoute, path: String) throws {
        try require((1...50_000).contains(route.points.count) && route.points.allSatisfy(\.isValid), "\(path) has invalid geometry.")
        try require(route.distance.isFinite && route.seconds.isFinite && route.distance >= 0 && route.seconds >= 0 && route.fetched >= 0,
                    "\(path) has an invalid distance, duration or timestamp.")
        try require(route.segments.count <= 10_000, "\(path) has too many segments.")
        for segment in route.segments {
            try require(segment.points.count <= 50_000 && segment.points.allSatisfy(\.isValid), "\(path) has invalid segment geometry.")
            try require(segment.distance.isFinite && segment.seconds.isFinite && segment.distance >= 0 && segment.seconds >= 0,
                        "\(path) has an invalid segment distance or duration.")
        }
    }

    private static func require(_ condition: Bool, _ message: String) throws {
        guard condition else { throw BackupError.invalid(message) }
    }

    private static func scenario(_ raw: Any, path: String) throws -> SavedScenario {
        let o = try Object(raw, path: path, keys: [
            "id", "name", "kind", "createdAt", "startIsReal", "startLat", "startLng", "startName", "destLat", "destLng",
            "destName", "travelMode", "speeds", "realism", "transitPref", "stops", "destAddress", "destPlaceId", "favorite",
            "lastUsedAt", "aliases", "frozenRoute", "defaultStayMinutes", "departureMillis", "arrivalActivity", "entranceLat",
            "entranceLng", "stayUntilLeave"
        ])
        let speedObject = try Object(o.value("speeds") ?? [String: Any](), path: "\(path).speeds", keys: Set(TravelMode.allCases.map(\.rawValue)))
        var speeds: [TravelMode: Double] = [:]
        for key in speedObject.keys {
            guard let mode = TravelMode(rawValue: key) else { throw BackupError.invalid("Unknown travel mode.") }
            speeds[mode] = try speedObject.number(key)
        }
        return try SavedScenario(
            id: o.string("id", default: ""), name: o.string("name"), kind: o.enumValue("kind", default: ScenarioKind.route),
            createdAt: o.integer("createdAt", default: 0), startIsReal: o.boolean("startIsReal", default: false),
            start: o.coordinate("start"), startName: o.string("startName", default: ""),
            dest: o.coordinate("dest"), destName: o.string("destName", default: ""), travelMode: o.enumValue("travelMode"),
            speeds: speeds, realism: o.enumValue("realism"), transitPref: o.optionalString("transitPref"),
            stops: o.array("stops", default: []).enumerated().map { try stop($0.element, path: "\(path).stops[\($0.offset)]") },
            destAddress: o.string("destAddress", default: ""), destPlaceId: o.string("destPlaceId", default: ""),
            favorite: o.boolean("favorite", default: false), lastUsedAt: o.integer("lastUsedAt", default: 0),
            aliases: o.array("aliases", default: []).map {
                guard let alias = $0 as? String else { throw BackupError.invalid("\(path).aliases must contain strings.") }
                return alias
            }, frozenRoute: o.value("frozenRoute").map { try route($0, path: "\(path).frozenRoute") },
            defaultStayMinutes: o.int("defaultStayMinutes", default: 0), departureMillis: o.optionalInteger("departureMillis"),
            arrivalActivity: o.enumValue("arrivalActivity", default: ArrivalActivity.building), entrance: o.coordinate("entrance"),
            stayUntilLeave: o.boolean("stayUntilLeave", default: false)
        )
    }

    private static func stop(_ raw: Any, path: String) throws -> SavedStop {
        let o = try Object(raw, path: path, keys: [
            "name", "lat", "lng", "dwell", "mode", "avgMph", "address", "placeId", "routingRealism", "routingTransitPref",
            "ownRoutingPreferences", "frozenRoute", "arriveByMillis", "arrivalActivity", "entranceLat", "entranceLng", "stayUntilLeave"
        ])
        return try SavedStop(
            name: o.string("name", default: ""), point: Coordinate(lat: o.number("lat"), lng: o.number("lng")),
            dwellMinutes: o.int("dwell", default: 30), mode: o.enumValue("mode"), avgMph: o.number("avgMph", default: 45),
            address: o.string("address", default: ""), placeId: o.string("placeId", default: ""),
            routingRealism: o.optionalEnum("routingRealism"), routingTransitPref: o.optionalString("routingTransitPref"),
            ownRoutingPreferences: o.boolean("ownRoutingPreferences", default: false),
            frozenRoute: o.value("frozenRoute").map { try route($0, path: "\(path).frozenRoute") },
            arriveByMillis: o.optionalInteger("arriveByMillis"), arrivalActivity: o.enumValue("arrivalActivity", default: ArrivalActivity.building),
            entrance: o.coordinate("entrance"), stayUntilLeave: o.boolean("stayUntilLeave", default: false)
        )
    }

    private static func route(_ raw: Any, path: String) throws -> ArchivedRoute {
        let o = try Object(raw, path: path, keys: ["points", "distance", "seconds", "fetched", "segments"])
        return try ArchivedRoute(points: points(o.array("points"), path: path), distance: o.number("distance"), seconds: o.number("seconds"),
            fetched: o.integer("fetched", default: 0), segments: o.array("segments", default: []).enumerated().map { entry in
                let segmentPath = "\(path).segments[\(entry.offset)]"
                let s = try Object(entry.element, path: segmentPath, keys: ["points", "distance", "seconds", "instruction", "maneuver"])
                return try RouteSegment(points: points(s.array("points"), path: segmentPath), distance: s.number("distance"),
                    seconds: s.number("seconds"), instruction: s.string("instruction", default: ""), maneuver: s.string("maneuver", default: ""))
            })
    }

    private static func points(_ raw: [Any], path: String) throws -> [Coordinate] {
        guard raw.count <= 50_000 else { throw BackupError.invalid("\(path) has too many route points.") }
        return try raw.map {
            guard let pair = $0 as? [Any], pair.count == 2 else { throw BackupError.invalid("\(path) requires [latitude, longitude] pairs.") }
            return try Coordinate(lat: numeric(pair[0], path: path), lng: numeric(pair[1], path: path))
        }
    }

    private static func scenarioJSON(_ item: SavedScenario) -> [String: Any] {
        var o: [String: Any] = [
            "id": item.id, "name": item.name, "kind": item.kind.rawValue, "createdAt": item.createdAt, "startIsReal": item.startIsReal,
            "startName": item.startName, "destName": item.destName, "travelMode": item.travelMode.rawValue,
            "speeds": Dictionary(uniqueKeysWithValues: item.speeds.map { ($0.key.rawValue, $0.value) }), "realism": item.realism.rawValue,
            "stops": item.stops.map(stopJSON), "destAddress": item.destAddress, "destPlaceId": item.destPlaceId,
            "favorite": item.favorite, "lastUsedAt": item.lastUsedAt, "aliases": item.aliases, "defaultStayMinutes": item.defaultStayMinutes,
            "arrivalActivity": item.arrivalActivity.rawValue, "stayUntilLeave": item.stayUntilLeave
        ]
        put(item.start, prefix: "start", into: &o); put(item.dest, prefix: "dest", into: &o); put(item.entrance, prefix: "entrance", into: &o)
        o["transitPref"] = item.transitPref; o["departureMillis"] = item.departureMillis
        o["frozenRoute"] = item.frozenRoute.map(routeJSON)
        return o
    }

    private static func stopJSON(_ stop: SavedStop) -> [String: Any] {
        var o: [String: Any] = [
            "name": stop.name, "lat": stop.point.lat, "lng": stop.point.lng, "dwell": stop.dwellMinutes, "mode": stop.mode.rawValue,
            "avgMph": stop.avgMph, "address": stop.address, "placeId": stop.placeId, "ownRoutingPreferences": stop.ownRoutingPreferences,
            "arrivalActivity": stop.arrivalActivity.rawValue, "stayUntilLeave": stop.stayUntilLeave
        ]
        put(stop.entrance, prefix: "entrance", into: &o)
        o["routingRealism"] = stop.routingRealism?.rawValue; o["routingTransitPref"] = stop.routingTransitPref
        o["arriveByMillis"] = stop.arriveByMillis; o["frozenRoute"] = stop.frozenRoute.map(routeJSON)
        return o
    }

    private static func routeJSON(_ route: ArchivedRoute) -> [String: Any] {
        ["points": route.points.map { [$0.lat, $0.lng] }, "distance": route.distance, "seconds": route.seconds,
         "fetched": route.fetched, "segments": route.segments.map { segment -> [String: Any] in
             ["points": segment.points.map { [$0.lat, $0.lng] }, "distance": segment.distance, "seconds": segment.seconds,
              "instruction": segment.instruction, "maneuver": segment.maneuver]
         }]
    }

    private static func put(_ point: Coordinate?, prefix: String, into object: inout [String: Any]) {
        if let point { object[prefix + "Lat"] = point.lat; object[prefix + "Lng"] = point.lng }
    }

    private static func numeric(_ value: Any, path: String) throws -> Double {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite else {
            throw BackupError.invalid("\(path) must be a finite number.")
        }
        return number.doubleValue
    }

    private struct Object {
        let raw: [String: Any]
        let path: String
        var keys: Dictionary<String, Any>.Keys { raw.keys }

        init(_ raw: Any, path: String, keys: Set<String>) throws {
            guard let object = raw as? [String: Any] else { throw BackupError.invalid("\(path) must be an object.") }
            guard Set(object.keys).isSubset(of: keys) else { throw BackupError.invalid("\(path) contains unsupported fields.") }
            self.raw = object; self.path = path
        }
        func value(_ key: String) -> Any? { raw[key] }
        func string(_ key: String, default fallback: String? = nil) throws -> String {
            guard let value = raw[key] else {
                if let fallback { return fallback }; throw BackupError.invalid("\(path).\(key) is missing.")
            }
            guard let result = value as? String else { throw BackupError.invalid("\(path).\(key) must be a string.") }
            return result
        }
        func optionalString(_ key: String) throws -> String? { try raw[key].map { _ in try string(key) } }
        func number(_ key: String, default fallback: Double? = nil) throws -> Double {
            guard let value = raw[key] else {
                if let fallback { return fallback }; throw BackupError.invalid("\(path).\(key) is missing.")
            }
            return try numeric(value, path: "\(path).\(key)")
        }
        func integer(_ key: String, default fallback: Int64? = nil) throws -> Int64 {
            guard let value = raw[key] else {
                if let fallback { return fallback }; throw BackupError.invalid("\(path).\(key) is missing.")
            }
            _ = try numeric(value, path: "\(path).\(key)")
            guard let number = value as? NSNumber,
                  NSNumber(value: number.int64Value).compare(number) == .orderedSame else {
                throw BackupError.invalid("\(path).\(key) must be an integer in range.")
            }
            return number.int64Value
        }
        func optionalInteger(_ key: String) throws -> Int64? { try raw[key].map { _ in try integer(key) } }
        func int(_ key: String, default fallback: Int64) throws -> Int {
            guard let result = Int(exactly: try integer(key, default: fallback)) else { throw BackupError.invalid("\(path).\(key) is out of range.") }
            return result
        }
        func boolean(_ key: String, default fallback: Bool) throws -> Bool {
            guard let value = raw[key] else { return fallback }
            guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
                throw BackupError.invalid("\(path).\(key) must be a boolean.")
            }
            return number.boolValue
        }
        func array(_ key: String, default fallback: [Any]? = nil) throws -> [Any] {
            guard let value = raw[key] else {
                if let fallback { return fallback }; throw BackupError.invalid("\(path).\(key) is missing.")
            }
            guard let array = value as? [Any] else { throw BackupError.invalid("\(path).\(key) must be an array.") }
            return array
        }
        func enumValue<T: RawRepresentable>(_ key: String, default fallback: T? = nil) throws -> T where T.RawValue == String {
            if raw[key] == nil, let fallback { return fallback }
            guard let result = T(rawValue: try string(key)) else { throw BackupError.invalid("\(path).\(key) is unsupported.") }
            return result
        }
        func optionalEnum<T: RawRepresentable>(_ key: String) throws -> T? where T.RawValue == String {
            try raw[key].map { _ in try enumValue(key) }
        }
        func coordinate(_ prefix: String) throws -> Coordinate? {
            let lat = prefix + "Lat", lng = prefix + "Lng"
            if raw[lat] == nil && raw[lng] == nil { return nil }
            return try Coordinate(lat: number(lat), lng: number(lng))
        }
    }
}
