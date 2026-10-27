import Foundation

public enum LocationOutputMode: String, Sendable { case device, preview }

public struct LocationOutputCapabilities: Equatable, Sendable {
    public let name: String
    public let mode: LocationOutputMode
    public let coordinate: Bool
    public let speed: Bool
    public let course: Bool
    public let altitude: Bool
    public let accuracy: Bool
    public init(name: String, mode: LocationOutputMode, coordinate: Bool = true,
                speed: Bool = false, course: Bool = false, altitude: Bool = false,
                accuracy: Bool = false) {
        self.name = name; self.mode = mode; self.coordinate = coordinate
        self.speed = speed; self.course = course; self.altitude = altitude; self.accuracy = accuracy
    }
}

/// Successful transport calls acknowledge only that command, never external-app behavior.
public protocol LocationOutput: Sendable {
    var capabilities: LocationOutputCapabilities { get }
    /// Connect is idempotent for an existing healthy connection with the same setup.
    func connect() async throws
    func send(_ coordinate: Coordinate) async throws
    func clear() async throws
}

public enum LocationOutputState: String, Sendable {
    case disconnected, connecting, ready, sending, active, clearing
    case awaitingRealLocation, restored, recoveryNeeded
}

public struct LocationObservation: Equatable, Sendable {
    public let coordinate: Coordinate
    public let timestamp: Date
    /// nil is unknown, never evidence that system simulation has stopped.
    public let isSimulatedBySoftware: Bool?
    public let horizontalAccuracy: Double
    public init(coordinate: Coordinate, timestamp: Date, isSimulatedBySoftware: Bool?,
                horizontalAccuracy: Double) {
        self.coordinate = coordinate; self.timestamp = timestamp
        self.isSimulatedBySoftware = isSimulatedBySoftware; self.horizontalAccuracy = horizontalAccuracy
    }
}

public struct LocationOutputSnapshot: Equatable, Sendable {
    public let state: LocationOutputState
    public let message: String
    public let capabilities: LocationOutputCapabilities
    public let plannedCoordinate: Coordinate?
    public let sentCoordinate: Coordinate?
    public let observedLocation: LocationObservation?
    /// Persist before device commands. Clear only after fresh real-location evidence.
    public let recoveryNeeded: Bool
}

/// Serializes every transport operation, even when an adapter ignores cancellation.
/// Stop invalidates pending success callbacks immediately, then clears after the in-flight
/// command completes. A second Start cannot overtake clear or unresolved restoration.
public actor LocationOutputSession {
    private let output: any LocationOutput
    private let now: @Sendable () -> Date
    private var state: LocationOutputState
    private var message: String
    private var plannedCoordinate: Coordinate?
    private var sentCoordinate: Coordinate?
    private var observedLocation: LocationObservation?
    private var needsRecovery: Bool
    private var generation: UInt64 = 0
    private var clearCompletedAt: Date?
    private var commandLocked = false
    private var commandWaiters: [CheckedContinuation<Void, Never>] = []

    public init(output: any LocationOutput, recoveryNeeded: Bool = false,
                now: @escaping @Sendable () -> Date = { Date() }) {
        self.output = output; self.now = now
        self.needsRecovery = recoveryNeeded && output.capabilities.mode == .device
        self.state = self.needsRecovery ? .recoveryNeeded : .disconnected
        self.message = self.needsRecovery ? "A previous device session needs recovery. Use Stop." : "Not connected."
    }

    public func snapshot() -> LocationOutputSnapshot {
        LocationOutputSnapshot(state: state, message: message, capabilities: output.capabilities,
                               plannedCoordinate: plannedCoordinate, sentCoordinate: sentCoordinate,
                               observedLocation: observedLocation, recoveryNeeded: needsRecovery)
    }

    @discardableResult public func connect() async -> LocationOutputSnapshot {
        guard state == .disconnected || state == .restored || state == .recoveryNeeded else { return snapshot() }
        // Relaunch recovery needs a transport before clear. Reconnecting must not turn
        // an unresolved prior device effect into a session that accepts new writes.
        let recoveryOnly = state == .recoveryNeeded
        generation &+= 1
        let token = generation
        state = .connecting
        message = "Connecting…"
        needsRecovery = output.capabilities.mode == .device
        clearCompletedAt = nil
        await acquireCommand()
        defer { releaseCommand() }
        guard token == generation else { return snapshot() }
        do {
            try Task.checkCancellation()
            try await output.connect()
            try Task.checkCancellation()
            guard token == generation else { return snapshot() }
            state = recoveryOnly ? .recoveryNeeded : .ready
            if recoveryOnly {
                message = "Connected for recovery. Use Stop, then verify a fresh real location."
            } else {
                message = output.capabilities.mode == .preview ? "Preview ready. No device output." : "Connected. Device output is not yet verified."
            }
        } catch {
            if token == generation { fail("Connection failed: \(error.localizedDescription)") }
        }
        return snapshot()
    }

    @discardableResult public func send(_ coordinate: Coordinate) async -> LocationOutputSnapshot {
        guard state == .ready || state == .active else { return snapshot() }
        guard coordinate.isValid, output.capabilities.coordinate else {
            message = "Coordinate output is invalid or unavailable."
            return snapshot()
        }
        let token = generation
        plannedCoordinate = coordinate
        state = .sending
        message = "Sending coordinate…"
        needsRecovery = output.capabilities.mode == .device
        await acquireCommand()
        defer { releaseCommand() }
        guard token == generation else { return snapshot() }
        do {
            try Task.checkCancellation()
            try await output.send(coordinate)
            try Task.checkCancellation()
            guard token == generation else { return snapshot() }
            sentCoordinate = coordinate
            state = .active
            message = output.capabilities.mode == .preview ? "Preview only. No device output." : "Coordinate sent. Verify the observed location independently."
        } catch {
            if token == generation { fail("Coordinate write failed: \(error.localizedDescription)") }
        }
        return snapshot()
    }

    /// Always attempts clear, including after a failed connection or an ambiguous write.
    /// Deliberately ignores caller cancellation: clearing an outstanding device effect
    /// remains necessary. Adapters should not abandon clear when their caller disappears.
    @discardableResult public func stop() async -> LocationOutputSnapshot {
        generation &+= 1
        let token = generation
        state = .clearing
        message = "Clearing output…"
        clearCompletedAt = nil
        await acquireCommand()
        defer { releaseCommand() }
        guard token == generation else { return snapshot() }
        let result = await Task.detached { [output] in
            do { try await output.clear(); return Result<Void, Error>.success(()) }
            catch { return Result<Void, Error>.failure(error) }
        }.value
        guard token == generation else { return snapshot() }
        switch result {
        case .success:
            if output.capabilities.mode == .preview {
                state = .disconnected; needsRecovery = false
                message = "Preview stopped. No device output was changed."
            } else {
                state = .awaitingRealLocation; needsRecovery = true
                clearCompletedAt = now()
                message = "Clear acknowledged. Awaiting a fresh non-simulated location."
            }
        case .failure(let error):
            fail("Clear failed: \(error.localizedDescription). Recovery is still needed.")
        }
        return snapshot()
    }

    public func observe(_ observation: LocationObservation) {
        let currentTime = now()
        guard observation.coordinate.isValid, observation.timestamp.timeIntervalSince1970.isFinite,
              observation.horizontalAccuracy.isFinite, observation.horizontalAccuracy >= 0,
              observation.timestamp <= currentTime else { return }
        if let previous = observedLocation, observation.timestamp <= previous.timestamp { return }
        observedLocation = observation
        guard state == .awaitingRealLocation, let clearCompletedAt,
              observation.timestamp > clearCompletedAt,
              currentTime.timeIntervalSince(observation.timestamp) <= 15,
              observation.isSimulatedBySoftware == false else { return }
        state = .restored
        needsRecovery = false
        message = "A fresh non-simulated location was observed after clear."
    }

    /// Call when the app suspends, the transport is lost, or another interruption occurs.
    /// Persist the returned recovery flag; a new process must not assume prior output cleared.
    @discardableResult public func markInterrupted(_ reason: String) -> LocationOutputSnapshot {
        generation &+= 1
        clearCompletedAt = nil
        if state != .disconnected && state != .restored { fail(reason) }
        return snapshot()
    }

    private func fail(_ reason: String) {
        needsRecovery = output.capabilities.mode == .device
        state = needsRecovery ? .recoveryNeeded : .disconnected
        message = reason
    }

    private func acquireCommand() async {
        if !commandLocked { commandLocked = true; return }
        await withCheckedContinuation { continuation in commandWaiters.append(continuation) }
    }

    private func releaseCommand() {
        if commandWaiters.isEmpty { commandLocked = false }
        else { commandWaiters.removeFirst().resume() }
    }
}

/// Explicit in-app preview adapter. It never claims to alter Core Location or another app.
public struct PreviewLocationOutput: LocationOutput {
    public let capabilities = LocationOutputCapabilities(name: "In-app preview", mode: .preview)
    public init() {}
    public func connect() async throws {}
    public func send(_ coordinate: Coordinate) async throws {
        guard coordinate.isValid else { throw PlaybackError.invalidRoute }
    }
    public func clear() async throws {}
}
