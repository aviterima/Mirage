import XCTest
@testable import MirageCore

final class PlaybackTests: XCTestCase {
    private let start = Coordinate(lat: 0, lng: 0)
    private let finish = Coordinate(lat: 0, lng: 1)

    func testGeometryAndDurationsAreValidated() throws {
        let invalidCoordinates = [Coordinate(lat: .nan, lng: 0), Coordinate(lat: 0, lng: .infinity),
                                  Coordinate(lat: 91, lng: 0), Coordinate(lat: 0, lng: -181)]
        XCTAssertThrowsError(try RoutePlayback(points: [start], duration: 10))
        for invalid in invalidCoordinates {
            XCTAssertThrowsError(try RoutePlayback(points: [start, invalid], duration: 10))
        }
        for invalid in [0.0, -1, .nan, .infinity] {
            XCTAssertThrowsError(try RoutePlayback(points: [start, finish], duration: invalid))
        }
        for invalid in [-1.0, .nan, .infinity] {
            XCTAssertThrowsError(try RoutePlayback(points: [start, finish], duration: 10, dwellDuration: invalid))
        }
    }

    func testElapsedTimeControlsProgressAndPauseExcludesWallTime() throws {
        var playback = try RoutePlayback(points: [start, finish], duration: 100)
        try playback.start(at: 1000)
        XCTAssertEqual(try playback.advance(to: 1025).coordinate.lng, 0.25, accuracy: 0.000001)
        try playback.pause(at: 1030)
        XCTAssertEqual(try playback.advance(to: 5000).elapsed, 30)
        try playback.resume(at: 6000)
        XCTAssertEqual(try playback.advance(to: 6020).coordinate.lng, 0.5, accuracy: 0.000001)
        XCTAssertEqual(playback.snapshot.state, .playing)
    }

    func testDwellPauseAndArrivalRetainEndpoint() throws {
        var playback = try RoutePlayback(points: [start, finish], duration: 10, dwellDuration: 5)
        try playback.start(at: 0)
        let arrival = try playback.advance(to: 12)
        XCTAssertEqual(arrival.state, .dwelling)
        XCTAssertEqual(arrival.coordinate, finish)
        XCTAssertEqual(arrival.dwellRemaining, 3)
        try playback.pause(at: 12)
        try playback.resume(at: 100)
        let done = try playback.advance(to: 103)
        XCTAssertEqual(done.state, .arrived)
        XCTAssertEqual(done.elapsed, 15)
        XCTAssertEqual(try playback.advance(to: 1000).coordinate, finish)
        XCTAssertThrowsError(try playback.start(at: 1001))
        playback.stop()
        XCTAssertEqual(try playback.start(at: 0).coordinate, start)
    }

    func testHoldFreezesAndSkipHonorsDwell() throws {
        var playback = try RoutePlayback(points: [start, finish], duration: 100, dwellDuration: 10)
        try playback.start(at: 0)
        try playback.pause(at: 20)
        let skipped = try playback.skipToArrival(at: 25)
        XCTAssertEqual(skipped.state, .dwelling)
        XCTAssertEqual(skipped.coordinate, finish)
        let held = try playback.hold(at: 27)
        XCTAssertEqual(held.state, .holding)
        XCTAssertEqual(try playback.advance(to: 1000), held)
        XCTAssertThrowsError(try playback.resume(at: 1001))
    }

    func testTimeValidationDoesNotMutateProgress() throws {
        var playback = try RoutePlayback(points: [start, finish], duration: 10)
        try playback.start(at: 20)
        let expected = try playback.advance(to: 25)
        for badTime in [24.0, -1, .infinity, .nan] {
            XCTAssertThrowsError(try playback.advance(to: badTime))
            XCTAssertEqual(playback.snapshot, expected)
        }
    }

    func testDistanceWeightedSegmentsAndDuplicatePoints() throws {
        var playback = try RoutePlayback(points: [start, start, Coordinate(lat: 0, lng: 0.1), finish], duration: 100)
        try playback.start(at: 0)
        XCTAssertEqual(try playback.advance(to: 50).coordinate.lng, 0.5, accuracy: 0.000001)
        XCTAssertEqual(playback.distanceMeters, 111_194.9266, accuracy: 0.01)
        var stationary = try RoutePlayback(points: [start, start], duration: 10)
        try stationary.start(at: 0)
        XCTAssertEqual(try stationary.advance(to: 5).coordinate, start)
        XCTAssertEqual(try stationary.advance(to: 10).state, .arrived)
    }

    func testAntimeridianUsesShortPathAndPolarGeometryRemainsFinite() throws {
        var playback = try RoutePlayback(points: [Coordinate(lat: 0, lng: 179), Coordinate(lat: 0, lng: -179)], duration: 10)
        try playback.start(at: 0)
        let midpoint = try playback.advance(to: 5).coordinate
        XCTAssertEqual(abs(midpoint.lng), 180, accuracy: 0.000001)
        XCTAssertLessThan(playback.distanceMeters, 225_000)
        for route in [[Coordinate(lat: 90, lng: -180), Coordinate(lat: 90, lng: 180)],
                      [Coordinate(lat: 0, lng: 0), Coordinate(lat: 0, lng: 180)]] {
            var polar = try RoutePlayback(points: route, duration: 10)
            try polar.start(at: 0)
            XCTAssertTrue(try polar.advance(to: 5).coordinate.isValid)
        }
    }
}
