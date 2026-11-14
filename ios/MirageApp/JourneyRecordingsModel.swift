import Foundation
import Combine
import MirageCore

/// Admission is on Main; a task chain preserves capture/save order on the storage actor.
@MainActor
final class JourneyRecordingsModel: ObservableObject {
    @Published private(set) var source: JourneySource?
    @Published private(set) var active: JourneySummary?
    @Published private(set) var library: [JourneySummary] = []
    @Published private(set) var busy = false
    @Published var status = "Recordings stay on this iPhone"
    var onRealRecordingEnded: (() -> Void)?
    private let store: JourneyStore?
    private var tail: Task<Void, Never>?
    private var pending = 0
    private var generation: UInt64 = 0
    var recording: Bool { source != nil || busy }
    var realActive: Bool { source == .real }

    init(store: JourneyStore? = nil) {
        if let store { self.store = store }
        else {
            do {
                var url = try PrivateFiles.directory().appendingPathComponent("Recordings", isDirectory: true)
                try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true,
                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
                var values = URLResourceValues(); values.isExcludedFromBackup = true
                try url.setResourceValues(values)
                self.store = JourneyStore(directory: url)
            } catch { self.store = nil; status = "Recording storage is unavailable" }
        }
        Task { await reload() }
    }

    func start(_ kind: JourneySource) {
        guard !recording, let store else { return }
        source = kind; busy = true; generation &+= 1
        let token = generation
        let date = Date(), uptime = ProcessInfo.processInfo.systemUptime
        enqueue {
            guard self.generation == token else { return }
            self.active = try await store.start(source: kind, at: date, monotonicTime: uptime, name: nil)
            // An immediate Stop may already be queued behind this start write.
            // Keep admission blocked until its finish command has completed.
            if self.source != nil { self.busy = false }
            self.status = kind == .real ? "Waiting for fresh verified real locations" :
                kind == .preview ? "Recording preview positions; iPhone location is unchanged" : "Recording acknowledged iPhone output"
            await self.reload()
        }
    }

    func captureReal(_ value: LocationObservation, simulationActive: Bool) {
        guard source == .real,
              JourneyRecordingPolicy.acceptReal(isSimulatedBySoftware: value.isSimulatedBySoftware,
                simulationActive: simulationActive, timestamp: value.timestamp, now: Date()) else { return }
        let observedUptime = ProcessInfo.processInfo.systemUptime + value.timestamp.timeIntervalSinceNow
        capture(value.coordinate, timestamp: value.timestamp, uptime: observedUptime, accuracy: value.horizontalAccuracy)
    }

    func captureOutput(_ point: Coordinate, mode: LocationOutputMode) {
        guard source == (mode == .preview ? JourneySource.preview : JourneySource.emulated) else { return }
        capture(point, timestamp: Date(), uptime: ProcessInfo.processInfo.systemUptime, accuracy: nil)
    }

    private func capture(_ point: Coordinate, timestamp: Date, uptime: Double, accuracy: Double?) {
        guard source != nil, let store else { return }
        let token = generation
        guard pending < 512 else { status = "Storage is slow; saving the captured locations"; finish(); return }
        enqueue {
            guard self.generation == token else { return }
            _ = try await store.append(coordinate: point, timestamp: timestamp, monotonicTime: uptime,
                horizontalAccuracy: accuracy, speed: nil)
            self.active = await store.active
            if let active = self.active, active.sampleCount > 0 {
                self.status = "\(active.sampleCount) locations recorded"
            }
        }
    }

    func markGap(_ reason: String) {
        guard source == .emulated || source == .preview, let store else { return }
        let token = generation
        enqueue { if self.generation == token { try await store.markGap(reason: reason) } }
    }

    func finish() {
        guard source != nil, let store else { return }
        let wasReal = source == .real
        source = nil; busy = true
        if wasReal { onRealRecordingEnded?() }
        let date = Date(), uptime = ProcessInfo.processInfo.systemUptime
        let token = generation
        // This lifecycle command is admitted even if the sample limit has been reached.
        enqueue {
            guard self.generation == token else { return }
            _ = try await store.finish(at: date, monotonicTime: uptime)
            self.active = nil; self.busy = false
            self.status = "Recording saved. You can rename it or replay it."
            await self.reload()
        }
    }

    func reload() async {
        do { library = try await store?.list() ?? [] }
        catch { status = "Could not read the recording library" }
    }

    func rename(_ id: String, to name: String) async {
        guard !busy, let store else { return }
        busy = true; defer { busy = false }
        do { _ = try await store.rename(id: id, name: name); await reload() }
        catch { status = "Could not rename recording. Use a name of 1–160 characters." }
    }

    func delete(_ id: String) async {
        guard !busy, active?.id != id, let store else { return }
        busy = true; defer { busy = false }
        do { try await store.delete(id: id); await reload() }
        catch { status = "Could not delete recording" }
    }

    func read(_ id: String) async throws -> RecordedJourney {
        guard !recording, let store else { throw CocoaError(.fileReadNoPermission) }
        return try await store.read(id: id)
    }

    private func enqueue(_ operation: @escaping @MainActor () async throws -> Void) {
        let previous = tail
        pending += 1
        tail = Task { [weak self] in
            await previous?.value
            guard let self else { return }
            defer { self.pending -= 1 }
            do { try await operation() }
            catch {
                self.generation &+= 1
                let wasReal = self.source == .real
                self.source = nil; self.active = nil; self.busy = true
                _ = await self.store?.interrupt()
                if wasReal { self.onRealRecordingEnded?() }
                self.status = "Recording interrupted. Previously stored locations remain recoverable."
                await self.reload()
                self.busy = false
            }
        }
    }
}
