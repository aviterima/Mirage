import Foundation
import XCTest
import MirageCore
@testable import Mirage

private actor FixtureDeviceOutput: LocationOutput {
    enum Operation: String { case connect, send, clear }
    nonisolated let capabilities = LocationOutputCapabilities(name: "Test device fixture", mode: .device)
    private let blockedOperation: Operation?
    private let onFirstSend: (@Sendable () -> Void)?
    private let onBlocked: (@Sendable () -> Void)?
    private var gate: CheckedContinuation<Void, Never>?
    private var released = false
    private var sentOnce = false
    private var events: [String] = []

    init(block: Operation? = nil, onBlocked: (@Sendable () -> Void)? = nil,
         onFirstSend: (@Sendable () -> Void)? = nil) {
        self.blockedOperation = block
        self.onBlocked = onBlocked
        self.onFirstSend = onFirstSend
    }

    func connect() async throws { await perform(.connect) }
    func send(_ coordinate: Coordinate) async throws { await perform(.send) }
    func clear() async throws { await perform(.clear) }
    func log() -> [String] { events }
    func release() { released = true; gate?.resume(); gate = nil }

    private func perform(_ operation: Operation) async {
        events.append("\(operation.rawValue).begin")
        if operation == .send && !sentOnce { sentOnce = true; onFirstSend?() }
        if operation == blockedOperation && !released {
            await withCheckedContinuation { continuation in
                gate = continuation
                onBlocked?()
            }
        }
        events.append("\(operation.rawValue).end")
    }
}

private final class FixtureObservationClock: @unchecked Sendable {
    private let lock = NSLock()
    private var seconds: Double = 100
    func now() -> Date { lock.lock(); defer { lock.unlock() }; return Date(timeIntervalSince1970: seconds) }
    func set(_ value: Double) { lock.lock(); defer { lock.unlock() }; seconds = value }
}

@MainActor
final class ProofModelTests: XCTestCase {
    private let recoveryKey = "deviceOutputMayNeedRecovery"
    private let point = Coordinate(lat: 32.2, lng: -110.9)

    func testChangingOutputModeUsesOnlyTheSelectedTransport() async throws {
        let firstSend = expectation(description: "Device fixture received its first coordinate")
        let output = FixtureDeviceOutput(onFirstSend: { firstSend.fulfill() })
        let context = try makeContext(output: output)
        defer { context.cleanDefaults() }
        let model = context.model

        model.mode = .preview
        model.outputModeChanged()
        await model.connect()
        await model.setPoint()
        await waitUntil("Preview emits a planned point") { model.sent != nil }
        await model.stop()
        let beforeDevice = await output.log()
        XCTAssertTrue(beforeDevice.isEmpty)
        XCTAssertFalse(context.defaults.bool(forKey: recoveryKey))

        model.mode = .device
        model.outputModeChanged()
        XCTAssertNil(model.sent)
        await model.connect()
        XCTAssertTrue(model.connected)
        await model.setPoint()
        await fulfillment(of: [firstSend], timeout: 3)
        await waitUntil("Device coordinate is acknowledged before Stop") { model.sent != nil }
        await model.stop()
        XCTAssertTrue(model.recoveryNeeded)
        await restore(context)
        XCTAssertFalse(model.recoveryNeeded)
        XCTAssertNotNil(model.sent, "Keep the prior sent coordinate as diagnostic history")
        await model.backgrounded()
        XCTAssertEqual(model.playbackStatus, "Stopped", "Historical output must not create a new recovery warning")
        XCTAssertFalse(model.recoveryNeeded)
        XCTAssertFalse(context.defaults.bool(forKey: recoveryKey))
        let deviceEvents = await output.log()
        XCTAssertTrue(deviceEvents.contains("send.begin"))
        XCTAssertTrue(deviceEvents.contains("clear.end"))

        model.mode = .preview
        model.outputModeChanged()
        await model.connect()
        await model.setPoint()
        await waitUntil("Second preview emits a planned point") { model.sent != nil }
        await model.stop()
        let afterPreview = await output.log()
        XCTAssertEqual(afterPreview, deviceEvents, "Preview must never reuse the prior device session")
        XCTAssertFalse(context.defaults.bool(forKey: recoveryKey))
    }

    func testConnectAlsoRejectsReusingSessionWhenModeChangesWithoutPickerCallback() async throws {
        let output = FixtureDeviceOutput()
        let context = try makeContext(output: output)
        defer { context.cleanDefaults() }
        let model = context.model
        model.mode = .preview
        await model.connect()
        await model.stop()
        // Exercise the defensive connect check separately from outputModeChanged().
        model.mode = .device
        await model.connect()
        let deviceEvents = await output.log()
        XCTAssertEqual(deviceEvents, ["connect.begin", "connect.end"])
        XCTAssertTrue(context.defaults.bool(forKey: recoveryKey))
        await model.stop()
    }

    func testStopDuringConnectCannotBecomeReadyOrLoseRecoveryMarker() async throws {
        let blocked = expectation(description: "Device connect is in flight")
        let output = FixtureDeviceOutput(block: .connect, onBlocked: { blocked.fulfill() })
        let context = try makeContext(output: output)
        defer { context.cleanDefaults() }
        let model = context.model
        let connection = Task { await model.connect() }
        await fulfillment(of: [blocked], timeout: 3)
        XCTAssertTrue(model.busy)
        XCTAssertTrue(model.canStop)
        XCTAssertTrue(context.defaults.bool(forKey: recoveryKey), "Persist before awaiting the device")

        let stop = Task { await model.stop() }
        await waitUntil("Stop invalidates the pending connection") { model.stopping }
        await model.connect()
        await model.setPoint()
        XCTAssertFalse(model.running)
        await output.release()
        await connection.value
        await stop.value

        XCTAssertFalse(model.connected)
        XCTAssertFalse(model.busy)
        XCTAssertFalse(model.stopping)
        XCTAssertFalse(model.canRun)
        XCTAssertTrue(model.recoveryNeeded)
        XCTAssertTrue(context.defaults.bool(forKey: recoveryKey))
        let events = await output.log()
        XCTAssertEqual(events, ["connect.begin", "connect.end", "clear.begin", "clear.end"])
        await restore(context)
        XCTAssertFalse(context.defaults.bool(forKey: recoveryKey))
    }

    func testRelaunchRecoveryConnectsOnlyForClearUntilFreshRealEvidence() async throws {
        let output = FixtureDeviceOutput()
        let context = try makeContext(output: output, recoveryNeeded: true)
        defer { context.cleanDefaults() }
        let model = context.model
        XCTAssertTrue(model.recoveryNeeded)
        await model.connect()
        XCTAssertFalse(model.connected)
        XCTAssertFalse(model.canRun)
        await model.setPoint()
        await model.playSynthetic()
        XCTAssertFalse(model.running)
        await model.stop()
        XCTAssertTrue(model.recoveryNeeded)

        context.clock.set(104)
        await model.observe(observation(at: 99, simulated: false))
        await model.observe(observation(at: 101, simulated: nil))
        await model.observe(observation(at: 102, simulated: true))
        XCTAssertTrue(model.recoveryNeeded)
        XCTAssertTrue(context.defaults.bool(forKey: recoveryKey))
        await model.observe(observation(at: 103, simulated: false))
        XCTAssertFalse(model.recoveryNeeded)
        XCTAssertFalse(context.defaults.bool(forKey: recoveryKey))
        XCTAssertFalse(model.connected, "Restoration must not implicitly restart output")
        let noWrites = await output.log()
        XCTAssertEqual(noWrites, ["connect.begin", "connect.end", "clear.begin", "clear.end"])
        await model.connect()
        XCTAssertTrue(model.canRun)
        await model.stop()
    }

    func testBackgroundDuringPendingWriteInvalidatesOutputAndClearsPauseControls() async throws {
        let blocked = expectation(description: "Coordinate write is in flight")
        let output = FixtureDeviceOutput(block: .send, onBlocked: { blocked.fulfill() })
        let context = try makeContext(output: output)
        defer { context.cleanDefaults() }
        let model = context.model
        await model.connect()
        model.duration = "3600"
        await model.playSynthetic()
        await fulfillment(of: [blocked], timeout: 3)
        model.pauseOrResume()
        XCTAssertTrue(model.paused)

        let background = Task { await model.backgrounded() }
        await waitUntil("Background marks output uncertain before the pending write finishes") { model.recoveryNeeded }
        XCTAssertFalse(model.connected)
        XCTAssertTrue(context.defaults.bool(forKey: recoveryKey))
        await output.release()
        await background.value
        XCTAssertFalse(model.running)
        XCTAssertFalse(model.paused)
        XCTAssertFalse(model.canPause)
        XCTAssertTrue(model.recoveryNeeded)
        let events = await output.log()
        XCTAssertEqual(events, ["connect.begin", "connect.end", "send.begin", "send.end"])
        await model.stop()
    }

    func testSecondStartCannotReplacePlaybackAndStopDuringWriteClearsOnce() async throws {
        let blocked = expectation(description: "First playback owns the only write loop")
        let output = FixtureDeviceOutput(block: .send, onBlocked: { blocked.fulfill() })
        let context = try makeContext(output: output)
        defer { context.cleanDefaults() }
        let model = context.model
        await model.connect()
        model.duration = "3600"
        await model.playSynthetic()
        await fulfillment(of: [blocked], timeout: 3)
        let originalRoute = model.route
        await model.setPoint()
        await model.playSynthetic()
        XCTAssertEqual(model.route, originalRoute)
        XCTAssertTrue(model.running)
        XCTAssertFalse(model.canRun)
        let stop = Task { await model.stop() }
        await waitUntil("Stop is permitted while a coordinate write is pending") { model.stopping }
        await output.release()
        await stop.value
        XCTAssertFalse(model.running)
        XCTAssertFalse(model.canPause)
        XCTAssertTrue(model.recoveryNeeded)
        let events = await output.log()
        XCTAssertEqual(events, ["connect.begin", "connect.end", "send.begin", "send.end", "clear.begin", "clear.end"])
    }

    func testImportedSyntheticNameStillExposesAndPlaysItsArchivedGeometry() async throws {
        let output = FixtureDeviceOutput()
        let context = try makeContext(output: output)
        defer { context.cleanDefaults() }
        let model = context.model
        model.mode = .preview
        model.outputModeChanged()
        let points = [Coordinate(lat: 32.2, lng: -110.9), Coordinate(lat: 32.201, lng: -110.902)]
        let item = SavedScenario(id: "name-collision", name: "Synthetic test route", kind: .route,
            start: points[0], dest: points[1],
            frozenRoute: ArchivedRoute(points: points, distance: 210, seconds: 60))
        let imported = try XCTUnwrap(BackupCodec.decode(BackupCodec.encode([item])).first)

        model.load(imported)
        XCTAssertTrue(model.hasLoadedGeometry, "The playback control uses explicit state, not the imported name")
        XCTAssertEqual(model.routeName, "Synthetic test route")
        XCTAssertEqual(model.route, points)
        XCTAssertFalse(model.running, "Loading a template must not start playback")
        await model.connect()
        await model.playLoadedRoute()
        XCTAssertTrue(model.running)
        XCTAssertEqual(model.route, points, "Loaded playback must not regenerate synthetic geometry")
        await waitUntil("Loaded archived geometry emits a preview point") { model.sent != nil }
        await model.stop()
        XCTAssertTrue(model.hasLoadedGeometry, "Stop preserves the loaded geometry for replay")

        model.load(SavedScenario(id: "place", name: "Sample place", kind: .place, dest: points[0]))
        XCTAssertFalse(model.hasLoadedGeometry)
        model.load(imported)
        await model.connect()
        await model.playSynthetic()
        XCTAssertFalse(model.hasLoadedGeometry, "Generating a synthetic route clears loaded-geometry state")
        XCTAssertNotEqual(model.route, points)
        await model.stop()
        let events = await output.log()
        XCTAssertTrue(events.isEmpty, "This regression uses preview only")
    }

    private struct Context {
        let model: ProofModel
        let defaults: UserDefaults
        let suiteName: String
        let clock: FixtureObservationClock
        func cleanDefaults() { defaults.removePersistentDomain(forName: suiteName) }
    }

    private func makeContext(output: FixtureDeviceOutput, recoveryNeeded: Bool = false) throws -> Context {
        let suiteName = "com.mirage.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        defaults.set(recoveryNeeded, forKey: recoveryKey)
        let clock = FixtureObservationClock()
        let model = ProofModel(defaults: defaults, deviceOutputFactory: { output },
                               scenarioStore: nil, observationClock: { clock.now() })
        return Context(model: model, defaults: defaults, suiteName: suiteName, clock: clock)
    }

    private func restore(_ context: Context) async {
        context.clock.set(102)
        await context.model.observe(observation(at: 101, simulated: false))
    }

    private func observation(at time: Double, simulated: Bool?) -> LocationObservation {
        LocationObservation(coordinate: point, timestamp: Date(timeIntervalSince1970: time),
                            isSimulatedBySoftware: simulated, horizontalAccuracy: 2)
    }

    private func waitUntil(_ reason: String, predicate: @MainActor () -> Bool) async {
        for _ in 0..<10_000 {
            if predicate() { return }
            await Task.yield()
        }
        XCTFail(reason)
    }
}
