import Foundation
import XCTest
@testable import MirageCore

private enum TestOutputError: Error { case failed }

private actor ControlledOutput: LocationOutput {
    enum Operation: String { case connect, send, clear }
    nonisolated let capabilities: LocationOutputCapabilities
    private let failure: Operation?
    private let blockedOperation: Operation?
    private var gate: CheckedContinuation<Void, Never>?
    private var blockedWaiter: CheckedContinuation<Void, Never>?
    private var blocked = false
    private var events: [String] = []
    private var concurrent = 0
    private var maximumConcurrent = 0

    init(failure: Operation? = nil, block: Operation? = nil, mode: LocationOutputMode = .device) {
        capabilities = LocationOutputCapabilities(name: "Controlled fixture", mode: mode)
        self.failure = failure; self.blockedOperation = block
    }
    func connect() async throws { try await perform(.connect) }
    func send(_ coordinate: Coordinate) async throws { try await perform(.send) }
    func clear() async throws { try await perform(.clear) }
    func log() -> [String] { events }
    func maxConcurrent() -> Int { maximumConcurrent }
    func waitUntilBlocked() async {
        if blocked { return }
        await withCheckedContinuation { blockedWaiter = $0 }
    }
    func unblock() { gate?.resume(); gate = nil }
    private func perform(_ operation: Operation) async throws {
        events.append("\(operation.rawValue).begin")
        concurrent += 1; maximumConcurrent = max(maximumConcurrent, concurrent)
        defer { concurrent -= 1; events.append("\(operation.rawValue).end") }
        if blockedOperation == operation {
            blocked = true
            await withCheckedContinuation { continuation in
                gate = continuation
                blockedWaiter?.resume(); blockedWaiter = nil
            }
        }
        if failure == operation { throw TestOutputError.failed }
    }
}

/// Date is only for restoration evidence; route progress uses injected monotonic time.
private final class TestDateClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value = Date(timeIntervalSince1970: 100)
    func now() -> Date { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ seconds: Double) { lock.lock(); defer { lock.unlock() }; value = Date(timeIntervalSince1970: seconds) }
}

final class LocationOutputTests: XCTestCase {
    private let point = Coordinate(lat: 32.2, lng: -110.9)

    func testPreviewNeverClaimsDeviceRestoration() async {
        let session = LocationOutputSession(output: PreviewLocationOutput())
        let connected = await session.connect()
        XCTAssertEqual(connected.state, .ready)
        XCTAssertFalse(connected.recoveryNeeded)
        let sent = await session.send(point)
        XCTAssertEqual(sent.capabilities.mode, .preview)
        XCTAssertEqual(sent.state, .active)
        let stopped = await session.stop()
        XCTAssertEqual(stopped.state, .disconnected)
        XCTAssertFalse(stopped.recoveryNeeded)
        XCTAssertNil(stopped.observedLocation)
    }

    func testConnectFailureStillAllowsClearAndRequiresRealEvidence() async {
        let output = ControlledOutput(failure: .connect)
        let session = LocationOutputSession(output: output)
        let failed = await session.connect()
        XCTAssertEqual(failed.state, .recoveryNeeded)
        XCTAssertTrue(failed.recoveryNeeded)
        let stopped = await session.stop()
        XCTAssertEqual(stopped.state, .awaitingRealLocation)
        XCTAssertTrue(stopped.recoveryNeeded)
        let log = await output.log()
        XCTAssertEqual(log, ["connect.begin", "connect.end", "clear.begin", "clear.end"])
    }

    func testWriteFailureAndClearFailureNeverClaimValidOutput() async {
        let failingWrite = LocationOutputSession(output: ControlledOutput(failure: .send))
        await failingWrite.connect()
        let write = await failingWrite.send(point)
        XCTAssertEqual(write.state, .recoveryNeeded)
        XCTAssertEqual(write.plannedCoordinate, point)
        XCTAssertNil(write.sentCoordinate)
        let failingClear = LocationOutputSession(output: ControlledOutput(failure: .clear))
        await failingClear.connect()
        await failingClear.send(point)
        let clear = await failingClear.stop()
        XCTAssertEqual(clear.state, .recoveryNeeded)
        XCTAssertTrue(clear.recoveryNeeded)
    }

    func testStopSerializesAfterPendingWriteAndInvalidatesItsSuccess() async {
        let output = ControlledOutput(block: .send)
        let session = LocationOutputSession(output: output)
        await session.connect()
        let sendTask = Task { await session.send(point) }
        await output.waitUntilBlocked()
        let stopTask = Task { await session.stop() }
        await waitForState(.clearing, session: session)
        let blockedStart = await session.connect()
        XCTAssertEqual(blockedStart.state, .clearing)
        let pendingLog = await output.log()
        XCTAssertEqual(pendingLog, ["connect.begin", "connect.end", "send.begin"])
        await output.unblock()
        let staleResult = await sendTask.value
        XCTAssertNotEqual(staleResult.state, .active)
        let stopped = await stopTask.value
        XCTAssertEqual(stopped.state, .awaitingRealLocation)
        XCTAssertNil(stopped.sentCoordinate)
        let log = await output.log()
        XCTAssertEqual(log, ["connect.begin", "connect.end", "send.begin", "send.end", "clear.begin", "clear.end"])
        let concurrency = await output.maxConcurrent()
        XCTAssertEqual(concurrency, 1)
    }

    func testStopDuringConnectCannotBecomeReadyLate() async {
        let output = ControlledOutput(block: .connect)
        let session = LocationOutputSession(output: output)
        let connectTask = Task { await session.connect() }
        await output.waitUntilBlocked()
        let stopTask = Task { await session.stop() }
        await waitForState(.clearing, session: session)
        await output.unblock()
        let connection = await connectTask.value
        XCTAssertNotEqual(connection.state, .ready)
        let stopped = await stopTask.value
        XCTAssertEqual(stopped.state, .awaitingRealLocation)
        let concurrency = await output.maxConcurrent()
        XCTAssertEqual(concurrency, 1)
    }

    func testCancelledAmbiguousWriteRequiresRecovery() async {
        let output = ControlledOutput(block: .send)
        let session = LocationOutputSession(output: output)
        await session.connect()
        let task = Task { await session.send(point) }
        await output.waitUntilBlocked()
        task.cancel()
        await output.unblock()
        let result = await task.value
        XCTAssertEqual(result.state, .recoveryNeeded)
        XCTAssertTrue(result.recoveryNeeded)
        XCTAssertNil(result.sentCoordinate)
        let stopped = await session.stop()
        XCTAssertEqual(stopped.state, .awaitingRealLocation)
    }

    func testInterruptionInvalidatesInFlightConnection() async {
        let output = ControlledOutput(block: .connect)
        let session = LocationOutputSession(output: output)
        let task = Task { await session.connect() }
        await output.waitUntilBlocked()
        let interrupted = await session.markInterrupted("App suspended")
        XCTAssertTrue(interrupted.recoveryNeeded)
        await output.unblock()
        let completed = await task.value
        XCTAssertEqual(completed.state, .recoveryNeeded)
        XCTAssertEqual(completed.message, "App suspended")
    }

    func testRestorationRequiresFreshPostClearExplicitRealMetadata() async {
        let clock = TestDateClock()
        let session = LocationOutputSession(output: ControlledOutput(), now: { clock.now() })
        await session.connect()
        await session.send(point)
        await session.stop() // clear completed at 100
        clock.set(200)
        for (timestamp, source) in [(99.0, false as Bool?), (100, false), (101, nil), (102, true), (180, false)] {
            await session.observe(LocationObservation(coordinate: point, timestamp: Date(timeIntervalSince1970: timestamp),
                                                      isSimulatedBySoftware: source, horizontalAccuracy: 5))
            let pending = await session.snapshot()
            XCTAssertEqual(pending.state, .awaitingRealLocation)
            XCTAssertTrue(pending.recoveryNeeded)
        }
        await session.observe(LocationObservation(coordinate: point, timestamp: Date(timeIntervalSince1970: 199),
                                                  isSimulatedBySoftware: false, horizontalAccuracy: 5))
        let restored = await session.snapshot()
        XCTAssertEqual(restored.state, .restored)
        XCTAssertFalse(restored.recoveryNeeded)
    }

    func testInvalidAndFutureObservationCannotClearRecovery() async {
        let clock = TestDateClock()
        let session = LocationOutputSession(output: ControlledOutput(), now: { clock.now() })
        await session.stop()
        clock.set(102)
        for sample in [
            LocationObservation(coordinate: point, timestamp: Date(timeIntervalSince1970: 103), isSimulatedBySoftware: false, horizontalAccuracy: 1),
            LocationObservation(coordinate: Coordinate(lat: .nan, lng: 0), timestamp: Date(timeIntervalSince1970: 101), isSimulatedBySoftware: false, horizontalAccuracy: 1),
            LocationObservation(coordinate: point, timestamp: Date(timeIntervalSince1970: 101), isSimulatedBySoftware: false, horizontalAccuracy: -1)
        ] { await session.observe(sample) }
        let snapshot = await session.snapshot()
        XCTAssertEqual(snapshot.state, .awaitingRealLocation)
        XCTAssertNil(snapshot.observedLocation)
    }

    func testPersistedRecoveryConnectsOnlyToClearAndNeverAllowsWrites() async {
        let output = ControlledOutput()
        let session = LocationOutputSession(output: output, recoveryNeeded: true)
        let attempted = await session.connect()
        XCTAssertEqual(attempted.state, .recoveryNeeded)
        XCTAssertTrue(attempted.recoveryNeeded)
        let forbiddenWrite = await session.send(point)
        XCTAssertNil(forbiddenWrite.sentCoordinate)
        let log = await output.log()
        XCTAssertEqual(log, ["connect.begin", "connect.end"])
        await session.stop()
        let blocked = await session.connect()
        XCTAssertEqual(blocked.state, .awaitingRealLocation)
        let finalLog = await output.log()
        XCTAssertEqual(finalLog, ["connect.begin", "connect.end", "clear.begin", "clear.end"])
    }

    func testCancelledStopStillFinishesClear() async {
        let output = ControlledOutput(block: .clear)
        let session = LocationOutputSession(output: output)
        await session.connect()
        await session.send(point)
        let stopTask = Task { await session.stop() }
        await output.waitUntilBlocked()
        stopTask.cancel()
        await output.unblock()
        let result = await stopTask.value
        XCTAssertEqual(result.state, .awaitingRealLocation)
        XCTAssertTrue(result.recoveryNeeded)
    }

    func testStaleRealObservationCannotOverrideNewerSimulatedLocation() async {
        let clock = TestDateClock()
        let session = LocationOutputSession(output: ControlledOutput(), now: { clock.now() })
        await session.stop()
        clock.set(105)
        await session.observe(LocationObservation(coordinate: point, timestamp: Date(timeIntervalSince1970: 104),
                                                  isSimulatedBySoftware: true, horizontalAccuracy: 1))
        await session.observe(LocationObservation(coordinate: point, timestamp: Date(timeIntervalSince1970: 103),
                                                  isSimulatedBySoftware: false, horizontalAccuracy: 1))
        let result = await session.snapshot()
        XCTAssertEqual(result.state, .awaitingRealLocation)
        XCTAssertEqual(result.observedLocation?.isSimulatedBySoftware, true)
    }

    func testInvalidCoordinateIsNeverSent() async {
        let output = ControlledOutput()
        let session = LocationOutputSession(output: output)
        await session.connect()
        let invalid = await session.send(Coordinate(lat: 100, lng: 0))
        XCTAssertEqual(invalid.state, .ready)
        XCTAssertNil(invalid.sentCoordinate)
        let log = await output.log()
        XCTAssertEqual(log, ["connect.begin", "connect.end"])
    }

    private func waitForState(_ expected: LocationOutputState, session: LocationOutputSession) async {
        for _ in 0..<10_000 {
            if await session.snapshot().state == expected { return }
            await Task.yield()
        }
        XCTFail("Session never reached \(expected)")
    }
}
