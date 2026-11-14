import Foundation
import XCTest
@testable import MirageCore

final class LocationRecordingTests: XCTestCase {
    private let origin = Coordinate(lat: 33, lng: -112)
    private func date(_ seconds: Double) -> Date { Date(timeIntervalSince1970: seconds) }
    private func fixture() throws -> (URL, JourneyStore) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Mirage-recording-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return (url, JourneyStore(directory: url))
    }

    func testRealPolicyRejectsUnknownMockOldFutureAndSimulationSources() {
        XCTAssertTrue(JourneyRecordingPolicy.acceptReal(isSimulatedBySoftware: false, simulationActive: false, timestamp: date(100), now: date(105)))
        for simulated in [true, nil] as [Bool?] {
            XCTAssertFalse(JourneyRecordingPolicy.acceptReal(isSimulatedBySoftware: simulated, simulationActive: false, timestamp: date(100), now: date(105)))
        }
        XCTAssertFalse(JourneyRecordingPolicy.acceptReal(isSimulatedBySoftware: false, simulationActive: true, timestamp: date(100), now: date(105)))
        XCTAssertFalse(JourneyRecordingPolicy.acceptReal(isSimulatedBySoftware: false, simulationActive: false, timestamp: date(100), now: date(111)))
        XCTAssertFalse(JourneyRecordingPolicy.acceptReal(isSimulatedBySoftware: false, simulationActive: false, timestamp: date(106), now: date(105)))
    }

    func testSavedJourneyReopensWithSamplesAndEditableName() async throws {
        let (url, store) = try fixture()
        defer { try? FileManager.default.removeItem(at: url) }
        let initial = try await store.start(source: .real, at: date(1000), monotonicTime: 10)
        XCTAssertTrue(initial.name.hasPrefix("Real journey"))
        let first = try await store.append(coordinate: origin, timestamp: date(1000), monotonicTime: 10, horizontalAccuracy: 4, speed: 2)
        let second = try await store.append(coordinate: Coordinate(lat: 33.001, lng: -112), timestamp: date(1001), monotonicTime: 11, horizontalAccuracy: 5, speed: 3)
        XCTAssertTrue(first); XCTAssertTrue(second)
        _ = try await store.rename(id: initial.id, name: "  Morning errands  ")
        let saved = try await store.finish(at: date(1002), monotonicTime: 12)
        XCTAssertEqual(saved?.name, "Morning errands"); XCTAssertEqual(saved?.state, .saved)
        let reopened = JourneyStore(directory: url)
        let restored = try await reopened.read(id: initial.id)
        let listed = try await reopened.list()
        let active = await reopened.active
        XCTAssertNil(active)
        XCTAssertEqual(listed.map(\.id), [initial.id])
        XCTAssertEqual(restored.summary.duration, 2)
        XCTAssertEqual(restored.samples.map(\.offset), [0, 1])
        XCTAssertEqual(restored.samples.map(\.timestamp), [date(1000), date(1001)])
        XCTAssertEqual(restored.samples.last?.horizontalAccuracy, 5)
        XCTAssertEqual(restored.samples.last?.speed, 3)
        XCTAssertEqual(restored.summary.name, "Morning errands")
    }

    func testSourcesRemainDistinctInNamesAndPersistedMetadata() async throws {
        let (url, store) = try fixture()
        defer { try? FileManager.default.removeItem(at: url) }
        var ids: [String] = []
        for source in [JourneySource.real, .emulated, .preview] {
            let summary = try await store.start(source: source, at: date(1000), monotonicTime: 10)
            ids.append(summary.id)
            _ = try await store.append(coordinate: origin, timestamp: date(1000), monotonicTime: 10)
            _ = try await store.finish(at: date(1001), monotonicTime: 11)
        }
        let reopened = JourneyStore(directory: url)
        let real = try await reopened.read(id: ids[0]).summary
        let emulated = try await reopened.read(id: ids[1]).summary
        let preview = try await reopened.read(id: ids[2]).summary
        XCTAssertEqual(real.source, .real); XCTAssertTrue(real.name.hasPrefix("Real journey"))
        XCTAssertEqual(emulated.source, .emulated); XCTAssertTrue(emulated.name.hasPrefix("Emulated day"))
        XCTAssertEqual(preview.source, .preview); XCTAssertTrue(preview.name.hasPrefix("Preview journey"))
    }

    func testMonotonicClockSurvivesWallTimeCorrectionAndRejectsInvalidSamples() async throws {
        let (url, store) = try fixture()
        defer { try? FileManager.default.removeItem(at: url) }
        let summary = try await store.start(source: .real, at: date(1000), monotonicTime: 10)
        _ = try await store.append(coordinate: origin, timestamp: date(1000), monotonicTime: 10, speed: 2)
        let backwardsWall = try await store.append(coordinate: Coordinate(lat: 33.01, lng: -112), timestamp: date(500), monotonicTime: 11, speed: 2)
        let backwardsClock = try await store.append(coordinate: origin, timestamp: date(1002), monotonicTime: 10.5)
        let duplicateClock = try await store.append(coordinate: origin, timestamp: date(1002), monotonicTime: 11)
        let invalidPoint = try await store.append(coordinate: Coordinate(lat: .nan, lng: -112), timestamp: date(1002), monotonicTime: 12)
        let invalidAccuracy = try await store.append(coordinate: origin, timestamp: date(1002), monotonicTime: 12, horizontalAccuracy: -1)
        let invalidSpeed = try await store.append(coordinate: origin, timestamp: date(1002), monotonicTime: 12, speed: -.infinity)
        XCTAssertTrue(backwardsWall)
        XCTAssertFalse(backwardsClock); XCTAssertFalse(duplicateClock); XCTAssertFalse(invalidPoint)
        XCTAssertFalse(invalidAccuracy); XCTAssertFalse(invalidSpeed)
        _ = try await store.finish(at: date(501), monotonicTime: 12)
        let read = try await store.read(id: summary.id)
        XCTAssertEqual(read.samples.map(\.offset), [0, 1])
        XCTAssertEqual(read.samples.map(\.timestamp), [date(1000), date(500)])
    }

    func testMovingDecimationFlushesLatestSampleAndStationaryTimingSurvives() async throws {
        let (url, store) = try fixture()
        defer { try? FileManager.default.removeItem(at: url) }
        let moving = try await store.start(source: .emulated, at: date(1000), monotonicTime: 10)
        for index in 0...12 {
            let offset = Double(index) / 10
            _ = try await store.append(coordinate: Coordinate(lat: 33 + offset / 1000, lng: -112), timestamp: date(1000 + offset),
                                       monotonicTime: 10 + offset, speed: 2)
        }
        let active = await store.active
        XCTAssertEqual(active?.sampleCount, 2)
        _ = try await store.finish(at: date(1001.2), monotonicTime: 11.2)
        let movingRead = try await store.read(id: moving.id)
        XCTAssertEqual(movingRead.samples.count, 3)
        XCTAssertEqual(movingRead.samples.last!.offset, 1.2, accuracy: 0.00001)

        let stationary = try await store.start(source: .real, at: date(2000), monotonicTime: 20)
        for offset in 0...60 {
            _ = try await store.append(coordinate: origin, timestamp: date(2000 + Double(offset)), monotonicTime: 20 + Double(offset), speed: 0)
        }
        _ = try await store.finish(at: date(2060), monotonicTime: 80)
        let held = try await store.read(id: stationary.id)
        XCTAssertEqual(held.samples.map(\.offset), [0, 30, 60])
        XCTAssertEqual(held.summary.duration, 60)
        XCTAssertTrue(held.gaps.isEmpty)
        XCTAssertFalse(try held.frame(at: 45).isGap)
        XCTAssertEqual(try held.coordinate(at: 45), origin)
    }

    func testSessionAndReceptionGapsFlushPendingSamplesAndNeverInterpolateJumps() async throws {
        let (url, store) = try fixture()
        defer { try? FileManager.default.removeItem(at: url) }
        let summary = try await store.start(source: .emulated, at: date(1000), monotonicTime: 10)
        _ = try await store.append(coordinate: origin, timestamp: date(1000), monotonicTime: 10, speed: 2)
        _ = try await store.append(coordinate: Coordinate(lat: 33.001, lng: -112), timestamp: date(1000.5), monotonicTime: 10.5, speed: 2)
        try await store.markGap(reason: "New simulated session")
        try await store.markGap(reason: "Repeated interruption")
        _ = try await store.append(coordinate: Coordinate(lat: 40, lng: -73), timestamp: date(1001), monotonicTime: 11, speed: 2)
        _ = try await store.append(coordinate: Coordinate(lat: 50, lng: -1), timestamp: date(1031), monotonicTime: 41, speed: 2)
        _ = try await store.finish(at: date(1031), monotonicTime: 41)
        let track = try await store.read(id: summary.id)
        XCTAssertEqual(track.samples.map(\.offset), [0, 0.5, 1, 31])
        XCTAssertEqual(track.gaps.count, 2)
        XCTAssertEqual(track.gaps.first?.reason, "New simulated session")
        XCTAssertTrue(track.samples[2].gapBefore); XCTAssertTrue(track.samples[3].gapBefore)
        XCTAssertEqual(try track.coordinate(at: 0.75), track.samples[1].coordinate)
        XCTAssertTrue(try track.frame(at: 0.75).isGap)
        XCTAssertEqual(try track.coordinate(at: 20), track.samples[2].coordinate)
        XCTAssertEqual(try track.coordinate(at: 31), track.samples[3].coordinate)
    }

    func testInterruptedAppendRecoversValidPrefixWithoutResumingRecording() async throws {
        let (url, store) = try fixture()
        defer { try? FileManager.default.removeItem(at: url) }
        let initial = try await store.start(source: .preview, at: date(1000), monotonicTime: 10)
        _ = try await store.append(coordinate: origin, timestamp: date(1000), monotonicTime: 10, speed: 2)
        _ = try await store.append(coordinate: Coordinate(lat: 33.01, lng: -112), timestamp: date(1001), monotonicTime: 11, speed: 2)
        _ = try await store.rename(id: initial.id, name: "Interrupted preview")
        let journal = url.appendingPathComponent(initial.id).appendingPathExtension("jsonl")
        let writer = try FileHandle(forWritingTo: journal)
        try writer.seekToEnd(); try writer.write(contentsOf: Data("{\"type\":\"sample\"".utf8))
        try writer.synchronize(); try writer.close()
        let reopened = JourneyStore(directory: url)
        let recovered = try await reopened.read(id: initial.id)
        let listed = try await reopened.list()
        let active = await reopened.active
        XCTAssertNil(active)
        XCTAssertEqual(recovered.summary.state, .interrupted)
        XCTAssertEqual(recovered.summary.source, .preview)
        XCTAssertEqual(recovered.summary.name, "Interrupted preview")
        XCTAssertEqual(recovered.samples.count, 2)
        XCTAssertEqual(recovered.summary.duration, 1)
        XCTAssertEqual(listed.single?.sampleCount, 2)
        _ = try await reopened.rename(id: initial.id, name: "Recovered preview")
        let renamed = try await reopened.read(id: initial.id)
        XCTAssertEqual(renamed.summary.name, "Recovered preview")
        XCTAssertEqual(renamed.summary.state, .interrupted)
    }

    func testInterruptPreservesDurablePrefixAndAllowsAnotherExplicitRecording() async throws {
        let (url, store) = try fixture()
        defer { try? FileManager.default.removeItem(at: url) }
        let first = try await store.start(source: .real, at: date(1000), monotonicTime: 10)
        _ = try await store.append(coordinate: origin, timestamp: date(1000), monotonicTime: 10, speed: 2)
        _ = try await store.append(coordinate: Coordinate(lat: 33.01, lng: -112), timestamp: date(1001), monotonicTime: 11, speed: 2)
        _ = try await store.append(coordinate: Coordinate(lat: 33.02, lng: -112), timestamp: date(1001.1), monotonicTime: 11.1, speed: 2)
        let interrupted = await store.interrupt()
        let recovered = try await store.read(id: first.id)
        XCTAssertEqual(interrupted?.state, .interrupted)
        XCTAssertEqual(recovered.summary.state, .interrupted)
        XCTAssertEqual(recovered.samples.count, 2)
        let next = try await store.start(source: .emulated, at: date(2000), monotonicTime: 20)
        XCTAssertNotEqual(first.id, next.id)
        let listed = try await store.list()
        XCTAssertEqual(listed.count, 2)
    }

    func testRejectedEditsAndIdentifiersPreserveRecordsAndDeleteIsScoped() async throws {
        let (url, store) = try fixture()
        defer { try? FileManager.default.removeItem(at: url) }
        let first = try await store.start(source: .real, at: date(1000), monotonicTime: 10, name: "Keep me")
        _ = try await store.append(coordinate: origin, timestamp: date(1000), monotonicTime: 10)
        do { try await store.delete(id: first.id); XCTFail("Active deletion must fail") }
        catch { XCTAssertEqual(error as? JourneyRecordingError, .recordingActive) }
        do { _ = try await store.rename(id: first.id, name: "  "); XCTFail("Empty name must fail") }
        catch { XCTAssertEqual(error as? JourneyRecordingError, .invalidName) }
        do { _ = try await store.read(id: "../outside"); XCTFail("Unsafe identifier must fail") }
        catch { XCTAssertEqual(error as? JourneyRecordingError, .invalidIdentifier) }
        do { _ = try await store.start(source: .preview, at: date(1000), monotonicTime: 10); XCTFail("Second start must fail") }
        catch { XCTAssertEqual(error as? JourneyRecordingError, .alreadyRecording) }
        _ = try await store.finish(at: date(1001), monotonicTime: 11)
        let second = try await store.start(source: .preview, at: date(2000), monotonicTime: 20)
        _ = try await store.finish(at: date(2001), monotonicTime: 21)
        try await store.delete(id: second.id)
        let remaining = try await store.list()
        let original = try await store.read(id: first.id)
        XCTAssertEqual(remaining.map(\.id), [first.id])
        XCTAssertEqual(original.summary.name, "Keep me")
        XCTAssertEqual(original.samples.first?.coordinate, origin)
    }

    func testDurationLimitDoesNotEraseCapturedLocations() async throws {
        let (url, store) = try fixture()
        defer { try? FileManager.default.removeItem(at: url) }
        let first = try await store.start(source: .real, at: date(1000), monotonicTime: 10)
        _ = try await store.append(coordinate: origin, timestamp: date(1000), monotonicTime: 10)
        do {
            _ = try await store.append(coordinate: origin, timestamp: date(2000), monotonicTime: 11 + JourneyRecordingPolicy.maximumDuration)
            XCTFail("Duration limit must fail")
        } catch { XCTAssertEqual(error as? JourneyRecordingError, .recordingLimit) }
        let read = try await store.read(id: first.id)
        XCTAssertEqual(read.samples.count, 1)
        _ = await store.interrupt()
    }

    func testReplayUsesOriginalSampleTimingAndTrimsOnlyUnknownLeadingTime() throws {
        let summary = JourneySummary(id: "fixture", name: "Timed", source: .real, startedAt: date(1000), duration: 25, sampleCount: 2, state: .saved)
        let samples = [JourneySample(offset: 10, timestamp: date(1010), coordinate: origin, speed: 2),
                       JourneySample(offset: 20, timestamp: date(1020), coordinate: Coordinate(lat: 33.01, lng: -112), speed: 2)]
        let journey = try RecordedJourney(summary: summary, samples: samples)
        XCTAssertEqual(journey.replayDuration, 15)
        XCTAssertEqual(try journey.coordinate(at: 0), origin)
        XCTAssertEqual(try journey.coordinate(at: 5).lat, 33.005, accuracy: 0.000001)
        XCTAssertEqual(try journey.coordinate(at: 10), samples[1].coordinate)
        XCTAssertEqual(try journey.coordinate(at: 100), samples[1].coordinate)
        XCTAssertEqual(try journey.frame(at: 100).elapsed, 15)
        XCTAssertThrowsError(try journey.frame(at: -1))
        XCTAssertThrowsError(try journey.frame(at: .nan))
        XCTAssertThrowsError(try journey.frame(at: .infinity))
    }

    func testReplayHoldsUnknownLongIntervalsAndUsesShortestDateLinePath() throws {
        let summary = JourneySummary(id: "fixture", name: "Gap", source: .real, startedAt: date(1000), duration: 30, sampleCount: 2, state: .saved)
        let first = JourneySample(offset: 0, timestamp: date(1000), coordinate: origin, speed: 2)
        let last = JourneySample(offset: 30, timestamp: date(1030), coordinate: Coordinate(lat: 40, lng: -73), speed: 2)
        let journey = try RecordedJourney(summary: summary, samples: [first, last])
        XCTAssertTrue(try journey.frame(at: 20).isGap)
        XCTAssertEqual(try journey.coordinate(at: 20), origin)
        XCTAssertEqual(try journey.coordinate(at: 30), last.coordinate)
        let dateline = try RecordedJourney(summary: summary, samples: [
            JourneySample(offset: 0, timestamp: date(1000), coordinate: Coordinate(lat: 0, lng: 179), speed: 2),
            JourneySample(offset: 10, timestamp: date(1010), coordinate: Coordinate(lat: 0, lng: -179), speed: 2)])
        XCTAssertEqual(abs(try dateline.coordinate(at: 5).lng), 180, accuracy: 0.000001)
    }

    func testReplayRejectsInvalidCoordinatesDuplicateTimesAndEmptyLocations() throws {
        let summary = JourneySummary(id: "fixture", name: "Validation", source: .real, startedAt: date(1000), duration: 10)
        let sample = JourneySample(offset: 0, timestamp: date(1000), coordinate: origin)
        XCTAssertThrowsError(try RecordedJourney(summary: summary, samples: [sample, sample]))
        XCTAssertThrowsError(try RecordedJourney(summary: summary, samples: [JourneySample(offset: 0, timestamp: date(1000), coordinate: Coordinate(lat: 91, lng: 0))]))
        XCTAssertThrowsError(try RecordedJourney(summary: summary, samples: [sample], gaps: [JourneyGap(offset: 2, reason: "A"), JourneyGap(offset: 1, reason: "B")]))
        let empty = try RecordedJourney(summary: summary, samples: [])
        XCTAssertThrowsError(try empty.frame(at: 0))
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
