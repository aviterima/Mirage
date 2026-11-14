import Foundation

public enum JourneySource: String, Codable, Sendable { case real, emulated, preview }
public enum JourneyState: String, Codable, Sendable { case recording, saved, interrupted }

public struct JourneySummary: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var source: JourneySource
    public var startedAt: Date
    public var endedAt: Date?
    public var duration: TimeInterval
    public var sampleCount: Int
    public var state: JourneyState
    public var gapCount: Int

    public init(id: String, name: String, source: JourneySource, startedAt: Date, endedAt: Date? = nil,
                duration: TimeInterval = 0, sampleCount: Int = 0, state: JourneyState = .recording, gapCount: Int = 0) {
        self.id = id; self.name = name; self.source = source; self.startedAt = startedAt; self.endedAt = endedAt
        self.duration = duration; self.sampleCount = sampleCount; self.state = state; self.gapCount = gapCount
    }
}

public struct JourneySample: Codable, Equatable, Sendable {
    public let offset: TimeInterval
    public let timestamp: Date
    public let coordinate: Coordinate
    public let horizontalAccuracy: Double?
    public let speed: Double?
    public let gapBefore: Bool

    public init(offset: TimeInterval, timestamp: Date, coordinate: Coordinate,
                horizontalAccuracy: Double? = nil, speed: Double? = nil, gapBefore: Bool = false) {
        self.offset = offset; self.timestamp = timestamp; self.coordinate = coordinate
        self.horizontalAccuracy = horizontalAccuracy; self.speed = speed; self.gapBefore = gapBefore
    }
    private enum CodingKeys: String, CodingKey { case offset, timestamp, latitude, longitude, horizontalAccuracy, speed, gapBefore }
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        offset = try values.decode(Double.self, forKey: .offset)
        timestamp = try values.decode(Date.self, forKey: .timestamp)
        coordinate = Coordinate(lat: try values.decode(Double.self, forKey: .latitude),
                                lng: try values.decode(Double.self, forKey: .longitude))
        horizontalAccuracy = try values.decodeIfPresent(Double.self, forKey: .horizontalAccuracy)
        speed = try values.decodeIfPresent(Double.self, forKey: .speed)
        gapBefore = try values.decode(Bool.self, forKey: .gapBefore)
    }
    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(offset, forKey: .offset); try values.encode(timestamp, forKey: .timestamp)
        try values.encode(coordinate.lat, forKey: .latitude); try values.encode(coordinate.lng, forKey: .longitude)
        try values.encodeIfPresent(horizontalAccuracy, forKey: .horizontalAccuracy)
        try values.encodeIfPresent(speed, forKey: .speed); try values.encode(gapBefore, forKey: .gapBefore)
    }
}

public struct JourneyGap: Codable, Equatable, Sendable {
    public let offset: TimeInterval
    public let reason: String
    public init(offset: TimeInterval, reason: String) { self.offset = offset; self.reason = reason }
}

public struct JourneyFrame: Equatable, Sendable {
    public let coordinate: Coordinate
    public let isGap: Bool
    public let elapsed: TimeInterval
    public let duration: TimeInterval
}

public enum JourneyRecordingError: Error, LocalizedError, Equatable {
    case alreadyRecording, invalidTime, invalidSample, invalidName, invalidIdentifier, invalidRecording
    case recordingLimit, libraryFull, noSamples, recordingActive, storageUnavailable
    public var errorDescription: String? {
        switch self {
        case .alreadyRecording: return "A journey recording is already active."
        case .invalidTime: return "The recording clock is invalid."
        case .invalidSample: return "The location sample is invalid."
        case .invalidName: return "Use a recording name of 1–160 characters."
        case .invalidIdentifier: return "The recording identifier is invalid."
        case .invalidRecording: return "The recording could not be read safely."
        case .recordingLimit: return "The recording reached its storage or 48-hour limit. Stop and save it."
        case .libraryFull: return "The recording library is full. Delete a recording first."
        case .noSamples: return "No locations were captured in this recording."
        case .recordingActive: return "Stop and save this recording before deleting it."
        case .storageUnavailable: return "Recording storage is unavailable. Earlier captured locations are preserved."
        }
    }
}

public enum JourneyRecordingPolicy {
    public static let movingInterval: TimeInterval = 1
    public static let stationaryInterval: TimeInterval = 30
    public static let continuityInterval: TimeInterval = 15
    public static let maximumDuration: TimeInterval = 48 * 60 * 60
    public static let maximumSamples = 180_000
    public static let maximumBytes = 64 * 1024 * 1024

    /// Unknown Core Location source information is never accepted as proof of real GPS.
    public static func acceptReal(isSimulatedBySoftware: Bool?, simulationActive: Bool,
                                  timestamp: Date, now: Date = Date()) -> Bool {
        let age = now.timeIntervalSince(timestamp)
        return isSimulatedBySoftware == false && !simulationActive && age.isFinite && age >= 0 && age <= 10
    }

    public static func suggestedName(source: JourneySource, at date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium; formatter.timeStyle = .short
        let prefix: String
        switch source { case .real: prefix = "Real journey"; case .emulated: prefix = "Emulated day"; case .preview: prefix = "Preview journey" }
        return "\(prefix) · \(formatter.string(from: date))"
    }

    fileprivate static func valid(_ sample: JourneySample) -> Bool {
        sample.offset.isFinite && sample.offset >= 0 && sample.offset <= maximumDuration &&
            sample.timestamp.timeIntervalSince1970.isFinite && sample.coordinate.isValid &&
            (sample.horizontalAccuracy.map { $0.isFinite && $0 >= 0 } ?? true) &&
            (sample.speed.map { $0.isFinite && $0 >= 0 } ?? true)
    }

    fileprivate static func stationary(_ a: JourneySample, _ b: JourneySample) -> Bool {
        guard (a.speed ?? 0) < 0.5, (b.speed ?? 0) < 0.5 else { return false }
        let latitude = (b.coordinate.lat - a.coordinate.lat) * .pi / 180
        let longitude = (b.coordinate.lng - a.coordinate.lng) * .pi / 180
        let h = pow(sin(latitude / 2), 2) + cos(a.coordinate.lat * .pi / 180) *
            cos(b.coordinate.lat * .pi / 180) * pow(sin(longitude / 2), 2)
        return 6_371_000 * 2 * asin(sqrt(min(1, max(0, h)))) < 3
    }
}

public struct RecordedJourney: Equatable, Sendable {
    public let summary: JourneySummary
    public let samples: [JourneySample]
    public let gaps: [JourneyGap]

    public init(summary: JourneySummary, samples: [JourneySample], gaps: [JourneyGap] = []) throws {
        guard summary.duration.isFinite, (0...JourneyRecordingPolicy.maximumDuration).contains(summary.duration),
              samples.count <= JourneyRecordingPolicy.maximumSamples,
              samples.allSatisfy(JourneyRecordingPolicy.valid),
              zip(samples, samples.dropFirst()).allSatisfy({ pair in pair.0.offset < pair.1.offset }),
              gaps.allSatisfy({ $0.offset.isFinite && (0...JourneyRecordingPolicy.maximumDuration).contains($0.offset) }),
              zip(gaps, gaps.dropFirst()).allSatisfy({ pair in pair.0.offset <= pair.1.offset }) else {
            throw JourneyRecordingError.invalidRecording
        }
        self.summary = summary; self.samples = samples; self.gaps = gaps
    }

    /// Leading time with no location is excluded. Zero playback time is the first captured sample.
    public var replayDuration: TimeInterval {
        guard let first = samples.first, let last = samples.last else { return 0 }
        return max(last.offset, summary.duration) - first.offset
    }

    public func coordinate(at elapsed: TimeInterval) throws -> Coordinate { try frame(at: elapsed).coordinate }

    /// The caller supplies its monotonic, pause-aware playback clock. Gaps hold the previous location.
    public func frame(at elapsed: TimeInterval) throws -> JourneyFrame {
        guard elapsed.isFinite, elapsed >= 0 else { throw JourneyRecordingError.invalidTime }
        guard let first = samples.first else { throw JourneyRecordingError.noSamples }
        let playbackTime = min(elapsed, replayDuration)
        let offset = first.offset + playbackTime
        var low = 0, high = samples.count
        while low < high {
            let middle = (low + high) / 2
            if samples[middle].offset <= offset { low = middle + 1 } else { high = middle }
        }
        let index = max(0, low - 1)
        let a = samples[index]
        let b = index + 1 < samples.count ? samples[index + 1] : nil
        low = 0; high = gaps.count
        while low < high {
            let middle = (low + high) / 2
            if gaps[middle].offset <= offset { low = middle + 1 } else { high = middle }
        }
        let gap = low > 0 ? gaps[low - 1] : nil
        let explicitGap = gap.map { $0.offset >= a.offset && (b == nil || $0.offset < b!.offset) } ?? false
        let discontinuity = b.map {
            $0.gapBefore || ($0.offset - a.offset > JourneyRecordingPolicy.continuityInterval &&
                !JourneyRecordingPolicy.stationary(a, $0))
        } ?? false
        let missingTail = b == nil && offset - a.offset > JourneyRecordingPolicy.continuityInterval
        let isGap = explicitGap || discontinuity || missingTail
        var point = a.coordinate
        if let b, !isGap, b.offset - a.offset <= JourneyRecordingPolicy.continuityInterval {
            let fraction = min(1, max(0, (offset - a.offset) / (b.offset - a.offset)))
            var delta = b.coordinate.lng - a.coordinate.lng
            if delta > 180 { delta -= 360 }; if delta < -180 { delta += 360 }
            var longitude = a.coordinate.lng + delta * fraction
            if longitude > 180 { longitude -= 360 }; if longitude < -180 { longitude += 360 }
            point = Coordinate(lat: a.coordinate.lat + (b.coordinate.lat - a.coordinate.lat) * fraction, lng: longitude)
        }
        return JourneyFrame(coordinate: point, isGap: isGap, elapsed: playbackTime, duration: replayDuration)
    }
}

/// Incremental, bounded private storage. The app supplies a protected directory excluded from backups.
/// Methods contain no suspension points: each mutation completes before the next actor message.
public actor JourneyStore {
    private struct Header: Codable { let version: Int; let summary: JourneySummary }
    private struct Entry: Codable {
        let type: String
        let sample: JourneySample?
        let gap: JourneyGap?
    }
    private final class Session {
        var summary: JourneySummary
        let startedMonotonic: TimeInterval
        var lastObserved: JourneySample?
        var lastWritten: JourneySample?
        var pendingGap = false
        init(summary: JourneySummary, monotonic: TimeInterval) { self.summary = summary; startedMonotonic = monotonic }
    }

    public let directory: URL
    private var session: Session?
    public var active: JourneySummary? { session?.summary }
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    public init(directory: URL) {
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .millisecondsSince1970
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .millisecondsSince1970
        self.directory = directory
        self.encoder = encoder; self.decoder = decoder
    }

    @discardableResult public func start(source: JourneySource, at date: Date = Date(), monotonicTime: TimeInterval,
                                        name: String? = nil) throws -> JourneySummary {
        guard session == nil else { throw JourneyRecordingError.alreadyRecording }
        guard monotonicTime.isFinite, monotonicTime >= 0, date.timeIntervalSince1970.isFinite else {
            throw JourneyRecordingError.invalidTime
        }
        try ensureDirectory()
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        guard files.filter({ $0.pathExtension == "json" }).count < 200 else { throw JourneyRecordingError.libraryFull }
        let summary = JourneySummary(id: UUID().uuidString.lowercased(),
            name: try cleanName(name ?? JourneyRecordingPolicy.suggestedName(source: source, at: date)), source: source, startedAt: date)
        try writeHeader(summary)
        session = Session(summary: summary, monotonic: monotonicTime)
        return summary
    }

    /// Returns false for invalid/out-of-order observations or intentionally reduced stationary samples.
    @discardableResult public func append(coordinate: Coordinate, timestamp: Date, monotonicTime: TimeInterval,
                                         horizontalAccuracy: Double? = nil, speed: Double? = nil) throws -> Bool {
        guard let current = session else { return false }
        guard monotonicTime.isFinite, monotonicTime >= current.startedMonotonic else { return false }
        let offset = monotonicTime - current.startedMonotonic
        guard offset <= JourneyRecordingPolicy.maximumDuration else { throw JourneyRecordingError.recordingLimit }
        let candidate = JourneySample(offset: offset, timestamp: timestamp, coordinate: coordinate,
                                      horizontalAccuracy: horizontalAccuracy, speed: speed)
        guard JourneyRecordingPolicy.valid(candidate) else { return false }
        if let previous = current.lastObserved {
            guard offset > previous.offset else { return false }
            if offset - previous.offset > JourneyRecordingPolicy.continuityInterval {
                try markGap(reason: "Location updates interrupted")
            }
        }
        let sample = JourneySample(offset: offset, timestamp: timestamp, coordinate: coordinate,
            horizontalAccuracy: horizontalAccuracy, speed: speed, gapBefore: current.pendingGap)
        current.lastObserved = sample
        current.summary.duration = offset
        if let written = current.lastWritten, !current.pendingGap {
            let interval = JourneyRecordingPolicy.stationary(written, sample) ?
                JourneyRecordingPolicy.stationaryInterval : JourneyRecordingPolicy.movingInterval
            if offset - written.offset < interval { return false }
        }
        try writeSample(sample, into: current)
        return true
    }

    public func markGap(reason: String = "Location unavailable") throws {
        guard let current = session, !current.pendingGap else { return }
        try flushLatest(current)
        let gap = JourneyGap(offset: current.lastObserved?.offset ?? 0, reason: String(reason.prefix(160)))
        try appendEntry(Entry(type: "gap", sample: nil, gap: gap), id: current.summary.id)
        current.pendingGap = true; current.summary.gapCount += 1
    }

    @discardableResult public func finish(at date: Date = Date(), monotonicTime: TimeInterval) throws -> JourneySummary? {
        guard let current = session else { return nil }
        guard monotonicTime.isFinite, monotonicTime >= current.startedMonotonic, date.timeIntervalSince1970.isFinite else {
            throw JourneyRecordingError.invalidTime
        }
        try flushLatest(current)
        var saved = current.summary
        saved.duration = max(saved.duration, min(JourneyRecordingPolicy.maximumDuration, monotonicTime - current.startedMonotonic))
        saved.endedAt = date; saved.state = .saved
        try writeHeader(saved)
        session = nil
        return saved
    }

    /// Release a failed session without another write; a later read recovers its durable prefix.
    @discardableResult public func interrupt() -> JourneySummary? {
        guard let current = session else { return nil }
        session = nil
        var result = current.summary
        result.state = .interrupted; result.duration = current.lastWritten?.offset ?? 0
        return result
    }

    public func list() throws -> [JourneySummary] {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .map { url in
                let metadata = try readHeader(id: url.deletingPathExtension().lastPathComponent)
                if metadata.id == session?.summary.id { return session!.summary }
                if metadata.state == .recording { return try recover(metadata).summary }
                return metadata
            }.sorted { $0.startedAt > $1.startedAt }
    }

    public func read(id: String) throws -> RecordedJourney {
        let summary: JourneySummary
        if id == session?.summary.id { summary = session!.summary }
        else { summary = try readHeader(id: id) }
        return try recover(summary)
    }

    @discardableResult public func rename(id: String, name: String) throws -> JourneySummary {
        var summary: JourneySummary
        if id == session?.summary.id { summary = session!.summary }
        else { summary = try read(id: id).summary }
        summary.name = try cleanName(name)
        try writeHeader(summary)
        if id == session?.summary.id { session!.summary = summary }
        return summary
    }

    public func delete(id: String) throws {
        guard id != session?.summary.id else { throw JourneyRecordingError.recordingActive }
        let metadata = try file(id: id, suffix: "json")
        guard FileManager.default.fileExists(atPath: metadata.path) else { throw JourneyRecordingError.invalidIdentifier }
        let journal = try file(id: id, suffix: "jsonl")
        if FileManager.default.fileExists(atPath: journal.path) { try FileManager.default.removeItem(at: journal) }
        try FileManager.default.removeItem(at: metadata)
    }

    private func flushLatest(_ current: Session) throws {
        if let latest = current.lastObserved, latest.offset != current.lastWritten?.offset { try writeSample(latest, into: current) }
    }
    private func writeSample(_ sample: JourneySample, into current: Session) throws {
        guard current.summary.sampleCount < JourneyRecordingPolicy.maximumSamples else { throw JourneyRecordingError.recordingLimit }
        try appendEntry(Entry(type: "sample", sample: sample, gap: nil), id: current.summary.id)
        current.lastWritten = sample; current.pendingGap = false; current.summary.sampleCount += 1
    }
    private func appendEntry(_ entry: Entry, id: String) throws {
        let url = try file(id: id, suffix: "jsonl")
        if !FileManager.default.fileExists(atPath: url.path) {
            guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw JourneyRecordingError.storageUnavailable }
        }
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        let size = try handle.seekToEnd()
        var bytes = try encoder.encode(entry); bytes.append(0x0a)
        guard size + UInt64(bytes.count) <= UInt64(JourneyRecordingPolicy.maximumBytes) else { throw JourneyRecordingError.recordingLimit }
        try handle.write(contentsOf: bytes); try handle.synchronize()
    }
    private func writeHeader(_ summary: JourneySummary) throws {
        try ensureDirectory()
        let url = try file(id: summary.id, suffix: "json")
        try encoder.encode(Header(version: 1, summary: summary)).write(to: url, options: .atomic)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.synchronize()
    }
    private func readHeader(id: String) throws -> JourneySummary {
        let handle = try FileHandle(forReadingFrom: file(id: id, suffix: "json"))
        defer { try? handle.close() }
        let data = try handle.read(upToCount: 8193) ?? Data()
        guard !data.isEmpty, data.count <= 8192 else { throw JourneyRecordingError.invalidRecording }
        let header = try decoder.decode(Header.self, from: data)
        let value = header.summary
        guard header.version == 1, value.id == id, value.duration.isFinite,
              (0...JourneyRecordingPolicy.maximumDuration).contains(value.duration),
              (0...JourneyRecordingPolicy.maximumSamples).contains(value.sampleCount), value.gapCount >= 0,
              value.startedAt.timeIntervalSince1970.isFinite, value.endedAt?.timeIntervalSince1970.isFinite != false else {
            throw JourneyRecordingError.invalidRecording
        }
        _ = try cleanName(value.name)
        return value
    }
    private func recover(_ metadata: JourneySummary) throws -> RecordedJourney {
        var samples: [JourneySample] = [], gaps: [JourneyGap] = []
        var damaged = false
        let url = try file(id: metadata.id, suffix: "jsonl")
        if FileManager.default.fileExists(atPath: url.path) {
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            var pending = Data(), readBytes = 0
            while !damaged {
                let chunk = try handle.read(upToCount: 8192) ?? Data()
                if chunk.isEmpty { if !pending.isEmpty { damaged = true }; break }
                readBytes += chunk.count
                guard readBytes <= JourneyRecordingPolicy.maximumBytes else { throw JourneyRecordingError.recordingLimit }
                pending.append(chunk)
                while let newline = pending.firstIndex(of: 0x0a) {
                    let line = Data(pending[..<newline])
                    pending.removeSubrange(...newline)
                    do {
                        guard line.count <= 4096 else { throw JourneyRecordingError.invalidRecording }
                        let entry = try decoder.decode(Entry.self, from: line)
                        if entry.type == "sample", let sample = entry.sample, entry.gap == nil {
                            guard JourneyRecordingPolicy.valid(sample), samples.count < JourneyRecordingPolicy.maximumSamples,
                                  samples.last.map({ sample.offset > $0.offset }) ?? true else { throw JourneyRecordingError.invalidRecording }
                            samples.append(sample)
                        } else if entry.type == "gap", let gap = entry.gap, entry.sample == nil {
                            guard gap.offset.isFinite, (0...JourneyRecordingPolicy.maximumDuration).contains(gap.offset),
                                  gaps.last.map({ gap.offset >= $0.offset }) ?? true, gap.reason.count <= 160 else {
                                throw JourneyRecordingError.invalidRecording
                            }
                            gaps.append(gap)
                        } else { throw JourneyRecordingError.invalidRecording }
                    } catch { damaged = true; break }
                }
                if pending.count > 4096 { damaged = true }
            }
        }
        var summary = metadata
        if metadata.id == session?.summary.id { summary.state = .recording }
        else if metadata.state == .recording || damaged || metadata.sampleCount > samples.count { summary.state = .interrupted }
        summary.sampleCount = samples.count; summary.gapCount = gaps.count
        summary.duration = summary.state == .interrupted ? samples.last?.offset ?? 0 : max(summary.duration, samples.last?.offset ?? 0)
        return try RecordedJourney(summary: summary, samples: samples, gaps: gaps)
    }
    private func ensureDirectory() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    private func file(id: String, suffix: String) throws -> URL {
        guard let uuid = UUID(uuidString: id), uuid.uuidString.lowercased() == id else { throw JourneyRecordingError.invalidIdentifier }
        return directory.appendingPathComponent(id).appendingPathExtension(suffix)
    }
    private func cleanName(_ name: String) throws -> String {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, clean.count <= 160 else { throw JourneyRecordingError.invalidName }
        return clean
    }
}
