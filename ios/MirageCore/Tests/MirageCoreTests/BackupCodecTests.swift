import Foundation
import XCTest
@testable import MirageCore

final class BackupCodecTests: XCTestCase {
    private func fixture() throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "android-backup-v1", withExtension: "json"))
        return try Data(contentsOf: url)
    }

    private func sample() -> SavedScenario {
        SavedScenario(id: "sample", name: "Synthetic place", kind: .place, dest: Coordinate(lat: 33.1, lng: -112.1))
    }

    private func changedItem(_ change: (inout [String: Any]) -> Void) throws -> Data {
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture()) as? [String: Any])
        var items = try XCTUnwrap(root["items"] as? [[String: Any]])
        change(&items[1])
        root["items"] = items
        return try JSONSerialization.data(withJSONObject: root)
    }

    func testAndroidShapedFixturePreservesEveryFieldAndStopOrder() throws {
        let data = try fixture()
        let items = try BackupCodec.decode(data)
        XCTAssertEqual(items.count, 3)
        let place = items[0]
        XCTAssertEqual(place.id, "synthetic-place")
        XCTAssertEqual(place.kind, .place)
        XCTAssertEqual(place.createdAt, 1_793_000_000_000)
        XCTAssertTrue(place.startIsReal)
        XCTAssertNil(place.start)
        XCTAssertEqual(place.dest, Coordinate(lat: 33.1, lng: -112.1))
        XCTAssertEqual(place.arrivalActivity, .outdoor)
        XCTAssertTrue(place.stayUntilLeave)
        XCTAssertEqual(place.aliases, ["Lookout", "Sample view"])
        let route = items[1]
        XCTAssertEqual(route.travelMode, .bike)
        XCTAssertEqual(route.speeds.count, 5)
        XCTAssertEqual(route.speeds[.fly], 500)
        XCTAssertEqual(route.realism, .busy)
        XCTAssertEqual(route.transitPref, "LESS_WALKING")
        XCTAssertEqual(route.departureMillis, 1_793_100_000_000)
        XCTAssertEqual(route.frozenRoute?.segments.count, 3)
        XCTAssertEqual(route.frozenRoute?.segments[1].points, [])
        XCTAssertEqual(route.frozenRoute?.segments[2].maneuver, "turn-right")
        let itinerary = items[2]
        XCTAssertEqual(itinerary.stops.map(\.name), ["Sample first stop", "Sample second stop"])
        XCTAssertEqual(itinerary.stops[0].dwellMinutes, 35)
        XCTAssertEqual(itinerary.stops[0].avgMph, 40.25)
        XCTAssertEqual(itinerary.stops[0].routingRealism, .busy)
        XCTAssertEqual(itinerary.stops[0].routingTransitPref, "LESS_WALKING")
        XCTAssertTrue(itinerary.stops[0].ownRoutingPreferences)
        XCTAssertEqual(itinerary.stops[0].arriveByMillis, 1_793_100_300_000)
        XCTAssertEqual(itinerary.stops[0].entrance, Coordinate(lat: 33.0011, lng: -112.0011))
        XCTAssertEqual(itinerary.stops[0].arrivalActivity, .office)
        XCTAssertEqual(itinerary.stops[0].frozenRoute?.fetched, 1_792_999_999_100)
        XCTAssertTrue(itinerary.stops[1].stayUntilLeave)
        // Structural equality catches loss of any original Android field, including optional metadata.
        let encoded = try BackupCodec.encode(items)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: data) as? NSDictionary,
                       try JSONSerialization.jsonObject(with: encoded) as? NSDictionary)
        XCTAssertEqual(try BackupCodec.decode(encoded), items)
    }

    func testMinimalAndroidRecordDefaultsAndOptionalFields() throws {
        let json = #"{"format":"mirage-backup","version":1,"items":[{"name":"Minimal","destLat":0,"destLng":0,"travelMode":"DRIVE","realism":"REALISTIC"}]}"#
        let item = try XCTUnwrap(BackupCodec.decode(json).first)
        XCTAssertEqual(item.kind, .route)
        XCTAssertEqual(item.defaultStayMinutes, 0)
        XCTAssertEqual(item.arrivalActivity, .building)
        XCTAssertNil(item.frozenRoute)
        XCTAssertNil(item.departureMillis)
        XCTAssertEqual(try BackupCodec.decode(BackupCodec.encode([item])), [item])
    }

    func testEveryArrivalActivityAndTravelSettingRoundTrips() throws {
        for activity in [ArrivalActivity.building, .table, .office, .conference, .outdoor, .parked, .trailhead] {
            for mode in TravelMode.allCases {
                for realism in Realism.allCases {
                    var item = sample()
                    item.arrivalActivity = activity; item.travelMode = mode; item.realism = realism
                    XCTAssertEqual(try BackupCodec.decode(BackupCodec.encode([item])), [item])
                }
            }
        }
    }

    func testIntegerTimestampsPreserveFullInt64Precision() throws {
        var item = sample()
        item.createdAt = Int64.max; item.lastUsedAt = 9_007_199_254_740_993; item.departureMillis = Int64.max
        XCTAssertEqual(try BackupCodec.decode(BackupCodec.encode([item])), [item])
    }

    func testByteLimitAppliesToEntireUTF8InputAndExport() throws {
        let valid = try BackupCodec.encode([sample()])
        let atLimit = valid + Data(repeating: 32, count: BackupCodec.maxBytes - valid.count)
        XCTAssertEqual(try BackupCodec.decode(atLimit), [sample()])
        XCTAssertThrowsError(try BackupCodec.decode(atLimit + Data([32]))) { XCTAssertEqual($0 as? BackupError, .tooLarge) }
        var item = sample()
        item.destAddress = String(repeating: "🌵", count: BackupCodec.maxBytes / 4 + 1)
        XCTAssertThrowsError(try BackupCodec.encode([item])) { XCTAssertEqual($0 as? BackupError, .tooLarge) }
    }

    func testItemLimitAndEmptyBackup() throws {
        XCTAssertEqual(try BackupCodec.decode(BackupCodec.encode([])), [])
        XCTAssertEqual(try BackupCodec.decode(BackupCodec.encode(Array(repeating: sample(), count: 1_000))).count, 1_000)
        XCTAssertThrowsError(try BackupCodec.encode(Array(repeating: sample(), count: 1_001)))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: BackupCodec.encode([sample()])) as? [String: Any])
        let encodedItems = try XCTUnwrap(root["items"] as? [Any])
        let itemJSON = try XCTUnwrap(encodedItems.first)
        let raw: [String: Any] = ["format": "mirage-backup", "version": 1, "items": Array(repeating: itemJSON, count: 1_001)]
        XCTAssertThrowsError(try BackupCodec.decode(JSONSerialization.data(withJSONObject: raw)))
    }

    func testRejectsUnsupportedFormatMalformedAndTrailingInput() throws {
        for text in ["", "[]", "{", #"{"format":"mirage-backup","version":2,"items":[]}"#,
                     #"{"format":"other","version":1,"items":[]}"#,
                     #"{"format":"mirage-backup","version":true,"items":[]}"#,
                     #"{"format":"mirage-backup","version":"1","items":[]}"#,
                     #"{"format":"mirage-backup","version":1,"items":[]} garbage"#] {
            XCTAssertThrowsError(try BackupCodec.decode(text), text)
        }
    }

    func testRejectsCredentialsAndUnknownFieldsAtEveryLevel() throws {
        let credentialJSON = #"{"format":"mirage-backup","version":1,"items":[],"pairingRecord":"not-a-real-record"}"#
        XCTAssertThrowsError(try BackupCodec.decode(credentialJSON))
        XCTAssertThrowsError(try BackupCodec.decode(changedItem { $0["api_key"] = "synthetic-key" }))
        XCTAssertThrowsError(try BackupCodec.decode(changedItem {
            $0["stops"] = [["name": "Sample", "lat": 0, "lng": 0, "mode": "WALK", "token": "synthetic-token"]]
        }))
        XCTAssertThrowsError(try BackupCodec.decode(changedItem {
            $0["frozenRoute"] = ["points": [[0, 0]], "distance": 0, "seconds": 0, "credentials": [:]]
        }))
        let output = try BackupCodec.encodeString(BackupCodec.decode(fixture()))
        for key in ["api_key", "install_id", "pairingRecord", "credentials"] { XCTAssertFalse(output.contains("\"\(key)\"")) }
    }

    func testRejectsWrongTypesAndFractionalIntegersInsteadOfCoercing() throws {
        let changes: [(inout [String: Any]) -> Void] = [
            { $0["favorite"] = 1 }, { $0["destLat"] = true }, { $0["destLat"] = "33.1" },
            { $0["createdAt"] = 1.5 }, { $0["defaultStayMinutes"] = 2.5 }, { $0["speeds"] = [] },
            { $0["stops"] = "stops" }, { $0["aliases"] = [1] }, { $0["departureMillis"] = NSNull() },
            { $0["travelMode"] = "HOVERCRAFT" }, { $0["realism"] = "IMPOSSIBLE" }, { $0["arrivalActivity"] = "UNKNOWN" }
        ]
        for change in changes {
            XCTAssertThrowsError(try BackupCodec.decode(changedItem(change)))
        }
    }

    func testRejectsOutOfBoundsCoordinatesAndIncompletePairs() throws {
        let changes: [(inout [String: Any]) -> Void] = [
            { $0["destLat"] = 90.01 }, { $0["startLng"] = -180.01 },
            { $0.removeValue(forKey: "destLat") }, { $0.removeValue(forKey: "entranceLng") }
        ]
        for change in changes {
            XCTAssertThrowsError(try BackupCodec.decode(changedItem(change)))
        }
        var item = sample(); item.dest = Coordinate(lat: .nan, lng: 0)
        XCTAssertThrowsError(try BackupCodec.encode([item]))
        item.dest = Coordinate(lat: 0, lng: .infinity)
        XCTAssertThrowsError(try BackupCodec.encode([item]))
    }

    func testRejectsInvalidStaySpeedAndTimestamps() throws {
        let changes: [(inout [String: Any]) -> Void] = [
            { $0["speeds"] = ["DRIVE": 0] }, { $0["speeds"] = ["WALK": 1001] },
            { $0["speeds"] = ["HOVERCRAFT": 45] }, { $0["defaultStayMinutes"] = -1 },
            { $0["defaultStayMinutes"] = 1441 }, { $0["createdAt"] = -1 },
            { $0["lastUsedAt"] = -1 }, { $0["departureMillis"] = -1 }
        ]
        for change in changes {
            XCTAssertThrowsError(try BackupCodec.decode(changedItem(change)))
        }
        for dwell in [-1, 1441] {
            var item = sample(); item.stops = [SavedStop(name: "Sample", point: Coordinate(lat: 0, lng: 0), dwellMinutes: dwell)]
            XCTAssertThrowsError(try BackupCodec.encode([item]))
        }
    }

    func testRejectsNamesAliasesStopsAndMissingDestinations() throws {
        let changes: [(inout [String: Any]) -> Void] = [
            { $0["name"] = " \n " }, { $0["name"] = String(repeating: "x", count: 161) },
            { $0["aliases"] = Array(repeating: "alias", count: 13) }, { $0["aliases"] = [String(repeating: "x", count: 81)] },
            { $0["kind"] = "ITINERARY" }, { $0.removeValue(forKey: "destLat"); $0.removeValue(forKey: "destLng") }
        ]
        for change in changes {
            XCTAssertThrowsError(try BackupCodec.decode(changedItem(change)))
        }
        var item = sample()
        item.stops = Array(repeating: SavedStop(name: "Sample", point: Coordinate(lat: 0, lng: 0)), count: 101)
        XCTAssertThrowsError(try BackupCodec.encode([item]))
    }

    func testRejectsMalformedArchivedRouteAndSegmentGeometry() throws {
        let cases: [[String: Any]] = [
            ["points": [], "distance": 0, "seconds": 0],
            ["points": [[0]], "distance": 0, "seconds": 0],
            ["points": [[0, 0, 0]], "distance": 0, "seconds": 0],
            ["points": [[91, 0]], "distance": 0, "seconds": 0],
            ["points": [[0, 0]], "distance": -1, "seconds": 0],
            ["points": [[0, 0]], "distance": 0, "seconds": -1],
            ["points": [[0, 0]], "distance": 0, "seconds": 0, "fetched": -1],
            ["points": [[0, 0]], "distance": 0, "seconds": 0,
             "segments": [["points": [[0, 181]], "distance": 1, "seconds": 1]]],
            ["points": [[0, 0]], "distance": 0, "seconds": 0,
             "segments": [["points": [], "distance": 1, "seconds": -1]]]
        ]
        for route in cases { XCTAssertThrowsError(try BackupCodec.decode(changedItem { $0["frozenRoute"] = route })) }
        var item = sample()
        item.frozenRoute = ArchivedRoute(points: Array(repeating: Coordinate(lat: 0, lng: 0), count: 50_001), distance: 0, seconds: 0)
        XCTAssertThrowsError(try BackupCodec.encode([item]))
        item.frozenRoute = ArchivedRoute(points: [Coordinate(lat: 0, lng: 0)], distance: 0, seconds: 0,
            segments: Array(repeating: RouteSegment(points: [], distance: 0, seconds: 0), count: 10_001))
        XCTAssertThrowsError(try BackupCodec.encode([item]))
    }

    func testInvalidLastItemCannotPartiallyReplaceStore() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("scenarios.json")
        let store = ScenarioStore(fileURL: url)
        try await store.save([sample()])
        let before = try Data(contentsOf: url)
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture()) as? [String: Any])
        var items = try XCTUnwrap(root["items"] as? [[String: Any]])
        items[2]["travelMode"] = "INVALID"
        root["items"] = items
        do { try await store.importBackup(JSONSerialization.data(withJSONObject: root)); XCTFail("Invalid import must throw") }
        catch { XCTAssertNotNil(error as? BackupError) }
        XCTAssertEqual(try Data(contentsOf: url), before)
        let loaded = try await store.load()
        XCTAssertEqual(loaded, [sample()])
    }

    func testMalformedUnsupportedAndOversizeImportsPreserveStoreBytes() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("scenarios.json")
        let store = ScenarioStore(fileURL: url)
        try await store.save([sample()])
        let before = try Data(contentsOf: url)
        for data in [Data("broken".utf8), Data(#"{"format":"mirage-backup","version":2,"items":[]}"#.utf8),
                     Data(repeating: 32, count: BackupCodec.maxBytes + 1)] {
            do { try await store.importBackup(data); XCTFail("Invalid import must throw") } catch {}
            XCTAssertEqual(try Data(contentsOf: url), before)
        }
    }

    func testFailedAtomicWritePreservesExistingStore() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("scenarios.json")
        let original = ScenarioStore(fileURL: url)
        try await original.save([sample()])
        let before = try Data(contentsOf: url)
        let failing = ScenarioStore(fileURL: url, atomicWriter: { _, _ in throw CocoaError(.fileWriteNoPermission) })
        do { try await failing.importBackup(fixture()); XCTFail("A failed replacement must throw") }
        catch { XCTAssertEqual((error as NSError).code, CocoaError.fileWriteNoPermission.rawValue) }
        XCTAssertEqual(try Data(contentsOf: url), before)
        let loaded = try await original.load()
        XCTAssertEqual(loaded, [sample()])
    }

    func testStoreImportExportAndReopenPreserveAllMetadata() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("nested/scenarios.json")
        let store = ScenarioStore(fileURL: url)
        let initially = try await store.load()
        XCTAssertEqual(initially, [])
        let expected = try BackupCodec.decode(fixture())
        let imported = try await store.importBackup(fixture())
        XCTAssertEqual(imported, expected)
        let reopened = ScenarioStore(fileURL: url)
        let loaded = try await reopened.load()
        XCTAssertEqual(loaded, expected)
        let exported = try await reopened.exportBackup()
        XCTAssertEqual(try BackupCodec.decode(exported), expected)
    }

    func testCorruptExistingStoreIsNotSilentlyReset() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("scenarios.json")
        let invalid = Data("corrupt saved content".utf8)
        try invalid.write(to: url)
        let store = ScenarioStore(fileURL: url)
        do { _ = try await store.load(); XCTFail("Corrupt store must throw") } catch {}
        XCTAssertEqual(try Data(contentsOf: url), invalid)
    }

    func testAppendReadsExistingStoreBeforeTheUIHasLoadedIt() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("scenarios.json")
        let original = ScenarioStore(fileURL: url)
        let existing = try BackupCodec.decode(fixture())
        try await original.save(existing)
        let newSession = ScenarioStore(fileURL: url)
        let appended = try await newSession.append(sample())
        XCTAssertEqual(appended, existing + [sample()])
        let loaded = try await newSession.load()
        XCTAssertEqual(loaded, existing + [sample()])
    }

    func testConcurrentAppendsDoNotLoseSavedItems() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = ScenarioStore(fileURL: directory.appendingPathComponent("scenarios.json"))
        let items = (0..<20).map { index -> SavedScenario in
            var item = sample(); item.id = "concurrent-\(index)"; item.name = "Synthetic place \(index)"
            return item
        }
        try await withThrowingTaskGroup(of: Void.self) { group in
            for item in items { group.addTask { _ = try await store.append(item) } }
            try await group.waitForAll()
        }
        let loaded = try await store.load()
        XCTAssertEqual(loaded.count, items.count)
        XCTAssertEqual(Set(loaded.map(\.id)), Set(items.map(\.id)))
    }

    func testAppendingToCorruptStorePreservesItsBytes() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("scenarios.json")
        let invalid = Data("corrupt saved content".utf8)
        try invalid.write(to: url)
        let store = ScenarioStore(fileURL: url)
        do { _ = try await store.append(sample()); XCTFail("Append must not replace a corrupt existing file") }
        catch { XCTAssertNotNil(error as? BackupError) }
        XCTAssertEqual(try Data(contentsOf: url), invalid)
    }
}
