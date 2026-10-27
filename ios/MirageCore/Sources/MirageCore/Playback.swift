import Foundation

public enum PlaybackError: Error, Equatable {
    case invalidRoute, invalidDuration, invalidTime, timeMovedBackwards, invalidTransition
}

public enum PlaybackState: String, Sendable {
    case ready, playing, paused, dwelling, arrived, holding, stopped
}

public struct PlaybackSnapshot: Equatable, Sendable {
    public let state: PlaybackState
    public let coordinate: Coordinate
    /// Active elapsed seconds, including endpoint dwell; paused time is excluded.
    public let elapsed: Double
    public let progress: Double
    public let dwellRemaining: Double
}

/// A deterministic synthetic route clock. The caller supplies monotonic time in seconds.
/// This class of route is not a substitute for road, transit or hiking directions.
public struct RoutePlayback: Sendable {
    public let points: [Coordinate]
    public let duration: Double
    public let dwellDuration: Double
    public let distanceMeters: Double
    public private(set) var state: PlaybackState = .ready
    private var cumulativeDistances: [Double]
    private var elapsed: Double = 0
    private var lastTimestamp: Double?

    public init(points: [Coordinate], duration: Double, dwellDuration: Double = 0) throws {
        guard (2...100_000).contains(points.count), points.allSatisfy(\.isValid) else {
            throw PlaybackError.invalidRoute
        }
        guard duration.isFinite, duration > 0, dwellDuration.isFinite, dwellDuration >= 0,
              (duration + dwellDuration).isFinite else { throw PlaybackError.invalidDuration }
        var distances = [0.0]
        for index in 1..<points.count {
            distances.append(distances[index - 1] + Self.distance(points[index - 1], points[index]))
        }
        self.points = points
        self.duration = duration
        self.dwellDuration = dwellDuration
        self.cumulativeDistances = distances
        self.distanceMeters = distances.last ?? 0
    }

    public var snapshot: PlaybackSnapshot {
        let progress = min(1, max(0, elapsed / duration))
        return PlaybackSnapshot(state: state, coordinate: coordinate(at: progress), elapsed: elapsed,
                                progress: progress,
                                dwellRemaining: max(0, dwellDuration - max(0, elapsed - duration)))
    }

    @discardableResult public mutating func start(at time: Double) throws -> PlaybackSnapshot {
        guard state == .ready || state == .stopped else { throw PlaybackError.invalidTransition }
        try validate(time)
        elapsed = 0
        lastTimestamp = time
        state = .playing
        return snapshot
    }

    @discardableResult public mutating func advance(to time: Double) throws -> PlaybackSnapshot {
        try validate(time)
        if let lastTimestamp, state == .playing || state == .dwelling {
            // Subtraction remains finite for nonnegative finite monotonic timestamps.
            elapsed = min(duration + dwellDuration, elapsed + (time - lastTimestamp))
            updatePhase()
        }
        lastTimestamp = time
        return snapshot
    }

    @discardableResult public mutating func pause(at time: Double) throws -> PlaybackSnapshot {
        guard state == .playing || state == .dwelling else { throw PlaybackError.invalidTransition }
        try advance(to: time)
        if state != .arrived { state = .paused }
        return snapshot
    }

    @discardableResult public mutating func resume(at time: Double) throws -> PlaybackSnapshot {
        guard state == .paused else { throw PlaybackError.invalidTransition }
        try validate(time)
        lastTimestamp = time
        updatePhase()
        return snapshot
    }

    /// Freeze the current coordinate until Stop; Hold is distinct from a resumable pause.
    @discardableResult public mutating func hold(at time: Double) throws -> PlaybackSnapshot {
        guard state != .ready && state != .stopped else { throw PlaybackError.invalidTransition }
        try advance(to: time)
        state = .holding
        return snapshot
    }

    /// Skip travel to the endpoint, honoring its dwell before the arrived state.
    @discardableResult public mutating func skipToArrival(at time: Double) throws -> PlaybackSnapshot {
        guard state == .playing || state == .paused || state == .dwelling else {
            throw PlaybackError.invalidTransition
        }
        try validate(time)
        elapsed = duration
        lastTimestamp = time
        updatePhase()
        return snapshot
    }

    @discardableResult public mutating func stop() -> PlaybackSnapshot {
        state = .stopped
        // Keep the final planned coordinate visible; device restoration is a separate operation.
        lastTimestamp = nil
        return snapshot
    }

    private func validate(_ time: Double) throws {
        guard time.isFinite, time >= 0 else { throw PlaybackError.invalidTime }
        if let lastTimestamp, time < lastTimestamp { throw PlaybackError.timeMovedBackwards }
    }

    private mutating func updatePhase() {
        if elapsed < duration { state = .playing }
        else if elapsed < duration + dwellDuration { state = .dwelling }
        else { state = .arrived }
    }

    private func coordinate(at progress: Double) -> Coordinate {
        if progress <= 0 { return points[0] }
        if progress >= 1 { return points[points.count - 1] }
        guard distanceMeters > 0 else { return points[0] }
        let target = distanceMeters * progress
        // Binary search permits long imported geometries without scanning every tick.
        var low = 1
        var high = cumulativeDistances.count - 1
        while low < high {
            let middle = (low + high) / 2
            if cumulativeDistances[middle] < target { low = middle + 1 } else { high = middle }
        }
        let span = cumulativeDistances[low] - cumulativeDistances[low - 1]
        let fraction = span > 0 ? (target - cumulativeDistances[low - 1]) / span : 0
        return Self.interpolate(points[low - 1], points[low], fraction: fraction)
    }

    private static func radians(_ degrees: Double) -> Double { degrees * .pi / 180 }
    private static func longitudeDelta(_ start: Double, _ end: Double) -> Double {
        var result = (end - start).truncatingRemainder(dividingBy: 360)
        if result > 180 { result -= 360 }
        if result < -180 { result += 360 }
        return result
    }
    private static func distance(_ a: Coordinate, _ b: Coordinate) -> Double {
        let latitude = radians(b.lat - a.lat)
        let longitude = radians(longitudeDelta(a.lng, b.lng))
        let haversine = pow(sin(latitude / 2), 2) + cos(radians(a.lat)) * cos(radians(b.lat)) * pow(sin(longitude / 2), 2)
        return 6_371_000 * 2 * asin(sqrt(min(1, max(0, haversine))))
    }
    private static func interpolate(_ a: Coordinate, _ b: Coordinate, fraction: Double) -> Coordinate {
        let lat1 = radians(a.lat), lon1 = radians(a.lng)
        let lat2 = radians(b.lat), lon2 = radians(b.lng)
        let angle = distance(a, b) / 6_371_000
        // Coincident and antipodal pairs have no unique great-circle interpolation.
        // Use a deterministic shortest-longitude path for those degenerate cases.
        if angle < 1e-10 || abs(.pi - angle) < 1e-7 {
            var longitude = a.lng + longitudeDelta(a.lng, b.lng) * fraction
            if longitude > 180 { longitude -= 360 }
            if longitude < -180 { longitude += 360 }
            return Coordinate(lat: a.lat + (b.lat - a.lat) * fraction, lng: longitude)
        }
        let first = sin((1 - fraction) * angle) / sin(angle)
        let second = sin(fraction * angle) / sin(angle)
        let x = first * cos(lat1) * cos(lon1) + second * cos(lat2) * cos(lon2)
        let y = first * cos(lat1) * sin(lon1) + second * cos(lat2) * sin(lon2)
        let z = first * sin(lat1) + second * sin(lat2)
        return Coordinate(lat: atan2(z, sqrt(x * x + y * y)) * 180 / .pi,
                          lng: atan2(y, x) * 180 / .pi)
    }
}
