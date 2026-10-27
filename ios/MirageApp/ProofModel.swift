import Foundation
import Combine
import MirageCore

@MainActor
final class ProofModel: ObservableObject {
    enum Mode: String, CaseIterable { case device = "iPhone output", preview = "Preview only" }
    @Published var mode: Mode = .device
    @Published var latitude = "37.3349"
    @Published var longitude = "-122.0090"
    @Published var duration = "60"
    @Published var host = "10.7.0.1"
    @Published var port = "62078"
    @Published var status = "Connect to begin the device proof"
    @Published var busy = false
    @Published var stopping = false
    @Published var connected = false
    @Published var running = false
    @Published var paused = false
    @Published var canPause = false
    @Published var playbackStatus = "Stopped"
    @Published var playbackProgress = 0.0
    @Published var recoveryNeeded: Bool
    @Published var planned: Coordinate?
    @Published var sent: Coordinate?
    @Published var route: [Coordinate] = []
    @Published var saved: [SavedScenario] = []
    @Published var libraryBusy = false
    @Published var savedName = "Test point"
    @Published var pairingImported = false
    @Published var routeName = "Synthetic test route"
    private var session: LocationOutputSession?
    private var lastSnapshotRevision: UInt64 = 0
    private var playback: RoutePlayback?
    private var loop: Task<Void, Never>?
    private var store: ScenarioStore?
    private var libraryRevision: UInt64 = 0
    private var isConfiguredPreview = false
    private let defaults: UserDefaults
    private let deviceOutputFactory: (() throws -> any LocationOutput)?
    private let observationClock: @Sendable () -> Date
    private static let recoveryKey = "deviceOutputMayNeedRecovery"

    init(defaults: UserDefaults = .standard,
         deviceOutputFactory: (() throws -> any LocationOutput)? = nil,
         scenarioStore: ScenarioStore? = nil,
         observationClock: @escaping @Sendable () -> Date = { Date() }) {
        self.defaults = defaults
        self.deviceOutputFactory = deviceOutputFactory
        self.observationClock = observationClock
        recoveryNeeded = defaults.bool(forKey: Self.recoveryKey)
        host = defaults.string(forKey: "deviceTunnelHost") ?? "10.7.0.1"
        port = defaults.string(forKey: "deviceTunnelPort") ?? "62078"
        if recoveryNeeded { status = "Previous device output needs recovery. Connect, then Stop and verify real location." }
        if deviceOutputFactory != nil {
            pairingImported = true
            store = scenarioStore
        } else {
            pairingImported = (try? PrivateFiles.pairingURL()).map { FileManager.default.fileExists(atPath: $0.path) } ?? false
            do {
                if let scenarioStore { store = scenarioStore }
                else { store = ScenarioStore(fileURL: try PrivateFiles.directory().appendingPathComponent("saved-plans.json")) }
            }
            catch { status = "Local storage is unavailable" }
        }
        Task { await reloadSaved() }
    }

    var coordinate: Coordinate? {
        guard let lat = Double(latitude), let lng = Double(longitude) else { return nil }
        let point = Coordinate(lat: lat, lng: lng)
        return point.isValid ? point : nil
    }
    var canRun: Bool { connected && !busy && !stopping && !running && coordinate != nil && !recoveryNeeded }
    var canChangeSetup: Bool { !busy && !stopping && !connected && !running && !recoveryNeeded }
    var canEditDeviceSetup: Bool { !busy && !stopping && !connected && !running }
    var canStop: Bool { session != nil && !stopping }

    func connect() async {
        guard !busy, !stopping, !running else { return }
        busy = true
        defer { busy = false }
        do {
            if session != nil, isConfiguredPreview != (mode == .preview) {
                guard !recoveryNeeded else { status = "Recover the previous device output first"; return }
                session = nil
                lastSnapshotRevision = 0
                planned = nil
                sent = nil
            }
            if session == nil {
                if mode == .preview {
                    guard !recoveryNeeded else { status = "Clear the previous device output before previewing"; return }
                    session = LocationOutputSession(output: PreviewLocationOutput(), now: observationClock)
                    lastSnapshotRevision = 0
                    isConfiguredPreview = true
                } else {
                    guard DeviceTransport.isAvailable || deviceOutputFactory != nil else {
                        status = "This build supports preview only. Use a device-enabled build for iPhone output."
                        return
                    }
                    guard pairingImported else { status = "Import this iPhone's pairing record in Setup first"; return }
                    guard let number = UInt16(port), number > 0 else { status = "Enter a valid developer-service port"; return }
                    let device: any LocationOutput
                    if let deviceOutputFactory { device = try deviceOutputFactory() }
                    else { device = try DeviceTransport(pairingRecordURL: PrivateFiles.pairingURL(), host: host, port: number) }
                    session = LocationOutputSession(output: device, recoveryNeeded: recoveryNeeded, now: observationClock)
                    lastSnapshotRevision = 0
                    isConfiguredPreview = false
                }
            }
            guard let session else { return }
            persistPossibleWrite()
            let snapshot = await session.connect()
            update(snapshot, from: session)
        } catch {
            status = "Device setup is incomplete. Import a pairing file, start the loopback tunnel, and use a device-enabled build."
        }
    }

    func resetConnection() async {
        guard !busy, !stopping, !running, !recoveryNeeded else { return }
        busy = true
        defer { busy = false }
        if let session { update(await session.stop(), from: session) }
        guard !recoveryNeeded else { connected = false; return }
        session = nil
        lastSnapshotRevision = 0
        connected = false
        status = "Choose output and connect"
    }

    func setPoint() async {
        guard canRun, let coordinate else { return }
        beginPlayback(points: [coordinate, coordinate], seconds: 1)
    }

    func playSynthetic() async {
        guard canRun, let start = coordinate, let seconds = Double(duration), seconds.isFinite,
              (1...3600).contains(seconds) else { status = "Enter valid coordinates and a duration from 1 to 3,600 seconds"; return }
        let north = min(89.99, start.lat + 0.001)
        let east = start.lng > 179.99 ? start.lng - 0.001 : start.lng + 0.001
        route = [start, Coordinate(lat: north, lng: start.lng), Coordinate(lat: north, lng: east)]
        routeName = "Synthetic test route"
        beginPlayback(points: route, seconds: seconds)
    }

    func playLoadedRoute() async {
        guard canRun, route.count >= 2, let seconds = Double(duration), seconds.isFinite,
              (1...3600).contains(seconds) else { status = "Load saved geometry and set a duration from 1 to 3,600 seconds"; return }
        beginPlayback(points: route, seconds: seconds)
    }

    private func beginPlayback(points: [Coordinate], seconds: Double) {
        guard canRun else { return }
        do {
            var candidate = try RoutePlayback(points: points, duration: seconds)
            try candidate.start(at: ProcessInfo.processInfo.systemUptime)
            playback = candidate
            updatePlaybackStatus()
            running = true
            paused = false
            canPause = true
            loop = Task { [weak self] in
                while !Task.isCancelled {
                    guard let self, let session = self.session, self.playback != nil else { return }
                    do {
                        try self.playback?.advance(to: ProcessInfo.processInfo.systemUptime)
                        self.updatePlaybackStatus()
                        let pauseAvailable = self.playback?.state == .playing || self.playback?.state == .dwelling || self.playback?.state == .paused
                        if self.canPause != pauseAvailable { self.canPause = pauseAvailable }
                        if let point = self.playback?.snapshot.coordinate {
                            self.persistPossibleWrite()
                            let snapshot = await session.send(point)
                            self.update(snapshot, from: session)
                            if snapshot.state == .recoveryNeeded {
                                self.running = false
                                self.paused = false
                                self.canPause = false
                                self.playbackStatus = "Interrupted; recovery required"
                                self.playback = nil
                                return
                            }
                        }
                        try await Task.sleep(nanoseconds: 200_000_000)
                    } catch {
                        if !Task.isCancelled { self.status = "Playback interrupted; use Stop to clear output" }
                        return
                    }
                }
            }
        } catch { status = "This route cannot be played: invalid geometry or timing" }
    }

    func pauseOrResume() {
        guard running, canPause, !busy, !stopping else { return }
        do {
            if paused { try playback?.resume(at: ProcessInfo.processInfo.systemUptime) }
            else { try playback?.pause(at: ProcessInfo.processInfo.systemUptime) }
            paused = playback?.state == .paused
            canPause = playback?.state == .playing || playback?.state == .dwelling || playback?.state == .paused
            updatePlaybackStatus()
        } catch { status = "Playback timing changed; Stop and reconnect" }
    }

    func hold() {
        guard running, !busy, !stopping else { return }
        do { try playback?.hold(at: ProcessInfo.processInfo.systemUptime); paused = false; canPause = false; updatePlaybackStatus() }
        catch { status = "Unable to hold this route; use Stop" }
    }

    func stop() async {
        guard !stopping else { return }
        stopping = true
        defer { stopping = false }
        let stoppingSession = session
        let clearing = Task { await stoppingSession?.stop() }
        loop?.cancel()
        await loop?.value
        loop = nil
        playback?.stop()
        playback = nil
        running = false
        paused = false
        canPause = false
        playbackStatus = "Stopped"
        if let snapshot = await clearing.value, let stoppingSession { update(snapshot, from: stoppingSession) }
        else { status = recoveryNeeded ? "Connect to clear the previous device output" : "Stopped" }
        connected = false
    }

    func backgrounded() async {
        guard running || connected || busy || recoveryNeeded || sent != nil else { return }
        if isConfiguredPreview { await stop(); return }
        if let session {
            update(await session.markInterrupted("App entered the background; device output is unverified. Return and Stop to recover."), from: session)
        }
        loop?.cancel()
        await loop?.value
        loop = nil
        running = false
        paused = false
        canPause = false
        playbackStatus = "Interrupted; recovery required"
        playback = nil
        connected = false
    }

    func observe(_ observation: LocationObservation) async {
        guard let session else { return }
        await session.observe(observation)
        update(await session.snapshot(), from: session)
    }

    private func update(_ snapshot: LocationOutputSnapshot, from source: LocationOutputSession) {
        guard source === session, snapshot.revision > lastSnapshotRevision else { return }
        lastSnapshotRevision = snapshot.revision
        if status != snapshot.message { status = snapshot.message }
        if planned != snapshot.plannedCoordinate { planned = snapshot.plannedCoordinate }
        if sent != snapshot.sentCoordinate { sent = snapshot.sentCoordinate }
        // Active device output also needs cleanup after process death, but should not block its next write.
        let needsRecovery = snapshot.state == .recoveryNeeded || snapshot.state == .awaitingRealLocation
        if recoveryNeeded != needsRecovery { recoveryNeeded = needsRecovery }
        if recoveryNeeded && connected { connected = false }
        else if (snapshot.state == .ready || snapshot.state == .active) && !connected { connected = true }
        else if (snapshot.state == .disconnected || snapshot.state == .restored) && connected { connected = false }
        if !isConfiguredPreview, defaults.bool(forKey: Self.recoveryKey) != snapshot.recoveryNeeded {
            defaults.set(snapshot.recoveryNeeded, forKey: Self.recoveryKey)
        }
    }

    private func persistPossibleWrite() {
        if !isConfiguredPreview, !defaults.bool(forKey: Self.recoveryKey) { defaults.set(true, forKey: Self.recoveryKey) }
    }

    private func updatePlaybackStatus() {
        guard let playback else { return }
        if playbackProgress != playback.snapshot.progress { playbackProgress = playback.snapshot.progress }
        let nextStatus: String
        switch playback.state {
        case .ready: nextStatus = "Ready"
        case .playing: nextStatus = "Playing"
        case .paused: nextStatus = "Paused"
        case .dwelling: nextStatus = "Staying at destination"
        case .arrived: nextStatus = "Arrived · holding endpoint"
        case .holding: nextStatus = "Holding current point"
        case .stopped: nextStatus = "Stopped"
        }
        if playbackStatus != nextStatus { playbackStatus = nextStatus }
    }

    func use(_ point: Coordinate) {
        guard !running, !busy else { return }
        latitude = String(format: "%.7f", point.lat)
        longitude = String(format: "%.7f", point.lng)
    }

    func savePoint() async {
        guard !libraryBusy, let point = coordinate, !savedName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              savedName.count <= 160, let store else { status = "Enter a name and valid coordinates before saving"; return }
        libraryBusy = true
        libraryRevision &+= 1
        defer { libraryBusy = false }
        let item = SavedScenario(id: UUID().uuidString, name: savedName, kind: .place,
            createdAt: Int64(Date().timeIntervalSince1970 * 1000), dest: point, destName: savedName)
        do { saved = try await store.append(item); status = "Place saved; playback has not started" }
        catch { status = "Could not save the place; the previous library is unchanged" }
    }

    func load(_ item: SavedScenario) {
        guard !running, !busy, !recoveryNeeded else { return }
        if item.kind == .place, let dest = item.dest {
            use(dest); route = []; routeName = "Synthetic test route"; status = "Saved place loaded; press Set test point to send it"
        } else if let archived = item.frozenRoute, !archived.points.isEmpty {
            use(archived.points[0]); route = archived.points; routeName = item.name
            duration = String(Int(min(3600, max(1, archived.seconds))))
            status = "Saved geometry loaded for proof playback. Scheduling and realism are not applied in this proof."
        } else { status = "This plan is preserved in the library. It needs the full planner before playback; no straight-line route was substituted." }
    }

    func importBackup(from url: URL) async {
        guard !libraryBusy, let store else { status = "Local storage is busy or unavailable"; return }
        libraryBusy = true
        libraryRevision &+= 1
        defer { libraryBusy = false }
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let values = try url.resourceValues(forKeys: [.fileSizeKey])
            guard let size = values.fileSize, size <= 2 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
            let file = try FileHandle(forReadingFrom: url)
            defer { try? file.close() }
            saved = try await store.importBackup(file.read(upToCount: 2 * 1024 * 1024 + 1) ?? Data())
            status = "Backup imported; no playback started"
        } catch { status = "Invalid or unreadable backup. The previous saved library is unchanged." }
    }

    func exportBackup() async -> Data? {
        do { return try await store?.exportBackup() }
        catch { status = "Unable to export saved plans"; return nil }
    }

    func importPairing(from url: URL) {
        guard canEditDeviceSetup else { return }
        do { try PrivateFiles.importPairing(from: url); pairingImported = true; setupChanged(); status = "Pairing file stored privately on this device" }
        catch { status = "Pairing file could not be imported. Select a valid device pairing plist." }
    }

    func setupChanged() {
        guard canEditDeviceSetup else { return }
        // Retain the durable recovery marker. A replacement session may connect only for clear.
        defaults.set(host, forKey: "deviceTunnelHost")
        defaults.set(port, forKey: "deviceTunnelPort")
        session = nil
        lastSnapshotRevision = 0
        connected = false
    }

    func outputModeChanged() {
        guard canChangeSetup else { return }
        session = nil
        lastSnapshotRevision = 0
        planned = nil
        sent = nil
        status = mode == .preview ? "Preview selected. Connect to begin; device location will not change." : "iPhone output selected. Connect with device setup to begin."
    }

    private func reloadSaved() async {
        let revision = libraryRevision
        do {
            let items = try await store?.load() ?? []
            if revision == libraryRevision { saved = items }
        } catch {
            if revision == libraryRevision { status = "Saved library cannot be read. It has been preserved; import a valid backup to replace it." }
        }
    }
}
