import XCTest
import MirageCore
@testable import Mirage

@MainActor
final class JourneyRecordingsModelTests: XCTestCase {
    private let point = Coordinate(lat: 33.5, lng: -112.0)
    private let other = Coordinate(lat: 33.6, lng: -112.1)

    private func directory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Mirage-recording-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func waitFor(_ description: String, _ condition: @MainActor () -> Bool) async {
        let deadline = Date().addingTimeInterval(4)
        while !condition() && Date() < deadline { try? await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertTrue(condition(), description)
    }

    func testRealRecordingAdmitsOnlyFreshVerifiedNonMockObservationsAndPersistsRename() async throws {
        let url = try directory()
        defer { try? FileManager.default.removeItem(at: url) }
        let model = JourneyRecordingsModel(store: JourneyStore(directory: url))
        var ended = 0
        model.onRealRecordingEnded = { ended += 1 }
        model.start(.real)
        await waitFor("Real recording starts") { model.active != nil && !model.busy }
        func observation(_ simulated: Bool?, age: Double = 0) -> LocationObservation {
            LocationObservation(coordinate: other, timestamp: Date().addingTimeInterval(-age),
                                isSimulatedBySoftware: simulated, horizontalAccuracy: 5)
        }
        model.captureReal(observation(nil), simulationActive: false)
        model.captureReal(observation(true), simulationActive: false)
        model.captureReal(observation(false), simulationActive: true)
        model.captureReal(observation(false, age: 60), simulationActive: false)
        model.captureReal(observation(false, age: -60), simulationActive: false)
        model.captureOutput(other, mode: .device)
        model.captureReal(LocationObservation(coordinate: point, timestamp: Date(),
            isSimulatedBySoftware: false, horizontalAccuracy: 4), simulationActive: false)
        model.finish()
        await waitFor("Queued capture is saved before finish completes") {
            !model.busy && model.source == nil && model.active == nil && model.library.count == 1 && model.library.allSatisfy { $0.state == .saved }
        }
        let summary = try XCTUnwrap(model.library.first)
        let saved = try await model.read(summary.id)
        XCTAssertEqual(summary.source, .real); XCTAssertEqual(summary.state, .saved)
        XCTAssertEqual(saved.samples.count, 1); XCTAssertEqual(saved.samples.first?.coordinate, point)
        XCTAssertEqual(ended, 1)
        await model.rename(summary.id, to: "Morning errands")
        let reloaded = JourneyRecordingsModel(store: JourneyStore(directory: url))
        await reloaded.reload()
        XCTAssertEqual(reloaded.library.first?.name, "Morning errands")
        let readback = try await reloaded.read(summary.id)
        XCTAssertEqual(readback.samples, saved.samples)
    }

    func testEmulatedAndPreviewSourcesStaySeparateAndImmediateSavePreservesQueuedSamples() async throws {
        let url = try directory()
        defer { try? FileManager.default.removeItem(at: url) }
        let model = JourneyRecordingsModel(store: JourneyStore(directory: url))
        model.start(.emulated)
        await waitFor("Emulated recording starts") { model.active != nil && !model.busy }
        model.captureOutput(other, mode: .preview)
        model.captureOutput(point, mode: .device)
        model.finish()
        await waitFor("Emulated recording saved") { !model.recording && model.active == nil && model.library.count == 1 && model.library.allSatisfy { $0.state == .saved } }
        let emulated = try XCTUnwrap(model.library.first)
        let first = try await model.read(emulated.id)
        XCTAssertEqual(first.summary.source, .emulated)
        XCTAssertEqual(first.samples.map(\.coordinate), [point])

        model.start(.preview)
        // Start, capture, gap and finish must retain their order even before the start write returns.
        model.captureOutput(other, mode: .device)
        model.captureOutput(point, mode: .preview)
        model.markGap("Fixture pause")
        model.finish()
        await waitFor("Preview start/capture/finish chain saved") { !model.recording && model.active == nil && model.library.count == 2 && model.library.allSatisfy { $0.state == .saved } }
        let preview = try XCTUnwrap(model.library.first { $0.source == .preview })
        let second = try await model.read(preview.id)
        XCTAssertEqual(second.samples.map(\.coordinate), [point])
        XCTAssertEqual(second.summary.gapCount, 1)
        XCTAssertFalse(model.realActive)
    }

    func testProofPreviewCanRecordSaveReplayAndStopWithoutDeviceOutput() async throws {
        let url = try directory()
        defer { try? FileManager.default.removeItem(at: url) }
        let suite = "Mirage.recording.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let recordings = JourneyRecordingsModel(store: JourneyStore(directory: url.appendingPathComponent("journeys")))
        var deviceCalls = 0
        let proof = ProofModel(defaults: defaults, deviceOutputFactory: {
            deviceCalls += 1
            throw NSError(domain: "Unexpected device output", code: 1)
        }, scenarioStore: ScenarioStore(fileURL: url.appendingPathComponent("scenarios.json")), recordingsModel: recordings)
        proof.mode = .preview
        await proof.connect()
        XCTAssertTrue(proof.connected)
        recordings.start(.preview)
        await waitFor("Preview capture starts") { recordings.active != nil && !recordings.busy }
        await proof.setPoint()
        await waitFor("Preview output is captured") { (recordings.active?.sampleCount ?? 0) > 0 }
        XCTAssertEqual(proof.status, "Preview only. No device output.")
        let capturedPoint = proof.sent
        await proof.stop()
        recordings.finish()
        await waitFor("Preview recording is saved") { !recordings.recording && recordings.active == nil && recordings.library.count == 1 && recordings.library.allSatisfy { $0.state == .saved } }
        let summary = try XCTUnwrap(recordings.library.first)
        XCTAssertEqual(summary.source, .preview)
        await proof.connect()
        XCTAssertTrue(proof.canReplayRecording)
        await proof.replayRecording(summary.id, speed: 10)
        await waitFor("Recorded preview starts") { proof.running && proof.sent == capturedPoint && proof.status == "Preview only. No device output." }
        XCTAssertFalse(proof.recoveryNeeded)
        await proof.stop()
        XCTAssertFalse(proof.running); XCTAssertEqual(proof.playbackStatus, "Stopped")
        XCTAssertEqual(proof.status, "Preview stopped. No device output was changed.")
        XCTAssertFalse(defaults.bool(forKey: "deviceOutputMayNeedRecovery"))
        XCTAssertEqual(deviceCalls, 0)
    }
}
