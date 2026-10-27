import SwiftUI
import MapKit
import MirageCore
import UniformTypeIdentifiers

@main
struct MirageApp: App {
    @StateObject private var model = ProofModel()
    @StateObject private var observer = LocationObserver()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            MirageRootView(model: model, observer: observer)
                .task { observer.onObservation = { value in Task { await model.observe(value) } } }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .background { Task { await model.backgrounded() } }
                }
        }
    }
}

struct MirageRootView: View {
    @ObservedObject var model: ProofModel
    @ObservedObject var observer: LocationObserver
    @State private var importingBackup = false
    @State private var importingPairing = false
    @State private var exporting = false
    @State private var exportDocument: BackupDocument?
    @State private var confirmImport = false
    @State private var pendingBackup: URL?
    @State private var showingMap = true

    var body: some View {
        TabView {
            NavigationStack {
                proof
                    .navigationTitle("Mirage")
                    .toolbar {
                        ToolbarItemGroup(placement: .keyboard) {
                            Spacer()
                            Button("Done") { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
                        }
                    }
            }.tabItem { Label("Device proof", systemImage: "location.circle") }
            NavigationStack { library.navigationTitle("Saved") }
                .tabItem { Label("Saved", systemImage: "bookmark") }
            NavigationStack { setup.navigationTitle("Setup") }
                .tabItem { Label("Setup", systemImage: "gearshape") }
        }
        .tint(.teal)
        .fileImporter(isPresented: $importingBackup, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url): pendingBackup = url; confirmImport = true
            case .failure: model.status = "Backup selection canceled or unavailable"
            }
        }
        .confirmationDialog("Replace the saved library with this backup?", isPresented: $confirmImport, titleVisibility: .visible) {
            Button("Replace saved library") {
                if let url = pendingBackup { Task { await model.importBackup(from: url) } }
                pendingBackup = nil
            }
            Button("Cancel", role: .cancel) { pendingBackup = nil }
        } message: { Text("The entire file is validated before replacing any saved places or plans. Playback will not start.") }
        .fileImporter(isPresented: $importingPairing, allowedContentTypes: [.data, .propertyList]) { result in
            if case .success(let url) = result { model.importPairing(from: url) }
        }
        .fileExporter(isPresented: $exporting, document: exportDocument, contentType: .json,
                      defaultFilename: "mirage-backup") { result in
            if case .failure = result { model.status = "Backup export did not complete" }
        }
    }

    private var proof: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Label("iPhone engineering proof", systemImage: "wrench.and.screwdriver")
                        .font(.headline)
                    Text("Test location output and recovery before the full iPhone planner is released.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Text(model.mode == .preview ? "PREVIEW ONLY · does not change iPhone location" : "IPHONE OUTPUT · Remote Pairing and a local tunnel required")
                        .font(.caption.bold()).accessibilityIdentifier("output-mode-banner")
                }
                .padding().frame(maxWidth: .infinity, alignment: .leading)
                .background(.teal.opacity(0.10), in: RoundedRectangle(cornerRadius: 16))

                Picker("Output", selection: $model.mode) {
                    ForEach(ProofModel.Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }.pickerStyle(.segmented).disabled(!model.canChangeSetup)
                    .onChange(of: model.mode) { _, _ in model.outputModeChanged() }

                Text(model.status).font(.callout).accessibilityIdentifier("output-status")
                    .frame(maxWidth: .infinity, alignment: .leading)
                if model.recoveryNeeded {
                    Label("Real-location recovery is not yet verified", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange).font(.headline)
                }

                HStack {
                    Button(model.connected ? "Connected" : "Connect") { Task { await model.connect() } }
                        .buttonStyle(.borderedProminent).disabled(model.busy || model.running || model.connected)
                        .accessibilityIdentifier("connect-output")
                    Button("Stop / Clear") { Task {
                        await model.stop()
                        if model.mode == .device && model.recoveryNeeded { observer.request() }
                    } }
                        .buttonStyle(.bordered).disabled(!model.canStop)
                        .accessibilityIdentifier("stop-output")
                    if model.busy { ProgressView().accessibilityLabel("Output operation in progress") }
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text("Test point").font(.title3.bold())
                    coordinateField("Latitude", value: $model.latitude, id: "latitude")
                    coordinateField("Longitude", value: $model.longitude, id: "longitude")
                    if model.coordinate == nil { Text("Enter latitude −90…90 and longitude −180…180.").foregroundStyle(.red) }
                    Button("Use last verified real location") {
                        if let fix = observer.lastReal { model.use(Coordinate(lat: fix.coordinate.latitude, lng: fix.coordinate.longitude)) }
                    }.disabled(observer.lastReal == nil || model.running || model.busy)
                    Button("Set test point") { Task { await model.setPoint() } }
                        .buttonStyle(.borderedProminent).disabled(!model.canRun || model.running)
                        .accessibilityIdentifier("set-test-point")
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text("Route playback").font(.title3.bold())
                    Text(model.playbackStatus).font(.subheadline.bold()).accessibilityIdentifier("playback-status")
                    if model.running { ProgressView(value: model.playbackProgress).accessibilityLabel("Route progress") }
                    Text("Synthetic routes are test geometry, not road directions. Arrival holds the endpoint until Stop.")
                        .font(.caption).foregroundStyle(.secondary)
                    TextField("Duration in seconds", text: $model.duration)
                        .textFieldStyle(.roundedBorder).keyboardType(.numberPad).disabled(model.running)
                        .accessibilityLabel("Route duration in seconds")
                    Button("Play synthetic route") { Task { await model.playSynthetic() } }
                        .buttonStyle(.borderedProminent).disabled(!model.canRun || model.running)
                        .accessibilityIdentifier("play-synthetic-route")
                    if model.hasLoadedGeometry {
                        Text(model.routeName).font(.subheadline)
                        Button("Play loaded geometry") { Task { await model.playLoadedRoute() } }
                            .disabled(!model.canRun || model.running)
                            .accessibilityIdentifier("play-loaded-geometry")
                    }
                    HStack {
                        Button(model.paused ? "Resume" : "Pause") { model.pauseOrResume() }
                            .disabled(!model.running || !model.canPause || model.busy || model.stopping).accessibilityIdentifier("pause-resume")
                        Button("Hold here") { model.hold() }.disabled(!model.running || model.busy || model.stopping)
                    }.buttonStyle(.bordered)
                }

                DisclosureGroup("Map and output observations", isExpanded: $showingMap) {
                    map.frame(height: 250).clipShape(RoundedRectangle(cornerRadius: 12))
                    coordinateRow("Planned", point: model.planned)
                    coordinateRow(model.mode == .preview ? "Preview position" : "Transport sent", point: model.sent)
                    coordinateRow("Core Location", point: observer.latest.map { Coordinate(lat: $0.coordinate.latitude, lng: $0.coordinate.longitude) })
                    Text(observer.status).font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                    if let fix = observer.latest {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Observed at \(fix.timestamp.formatted(date: .omitted, time: .standard))")
                            Text("Accuracy: \(fix.horizontalAccuracy, specifier: "%.1f") m")
                            Text(fix.speed >= 0 ? String(format: "Observed speed: %.2f m/s", fix.speed) : "Observed speed: unavailable")
                            Text(fix.course >= 0 ? String(format: "Observed course: %.1f°", fix.course) : "Observed course: unavailable")
                            Text(fix.verticalAccuracy >= 0 ? String(format: "Observed altitude: %.1f m", fix.altitude) : "Observed altitude: unavailable")
                            if let interval = observer.sampleInterval { Text("Sample interval: \(interval, specifier: "%.2f") s") }
                        }.font(.caption.monospacedDigit()).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Button("Observe iPhone location") { observer.request() }.buttonStyle(.bordered)
                    Text("A transport acknowledgement does not prove another app used the location. Verify Google Maps on the phone separately.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if model.connected && !model.running {
                    Button("Clear connection and change output") { Task {
                        await model.resetConnection()
                        if model.mode == .device && model.recoveryNeeded { observer.request() }
                    } }
                        .disabled(model.busy)
                }
            }.padding()
        }.scrollDismissesKeyboard(.interactively)
    }

    private var map: some View {
        Map {
            if model.route.count >= 2 {
                MapPolyline(coordinates: model.route.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) })
                    .stroke(.teal, lineWidth: 4)
            }
            if let point = model.planned ?? model.coordinate {
                Marker("Planned", coordinate: CLLocationCoordinate2D(latitude: point.lat, longitude: point.lng)).tint(.teal)
            }
            if let location = observer.latest {
                Marker("Observed", coordinate: location.coordinate).tint(.orange)
            }
        }.accessibilityLabel("Planned route and separately observed location")
    }

    private var library: some View {
        List {
            Section("Save a place") {
                TextField("Place name", text: $model.savedName)
                Text("Uses the test point coordinates. Saving does not send location.").font(.caption)
                Button("Save current test point") { Task { await model.savePoint() } }
                    .disabled(model.libraryBusy || model.coordinate == nil || model.savedName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            Section("Places and plans") {
                if model.saved.isEmpty { Text("No saved places or plans").foregroundStyle(.secondary) }
                ForEach(Array(model.saved.enumerated()), id: \.offset) { _, item in
                    Button { model.load(item) } label: {
                        VStack(alignment: .leading) {
                            Text(item.name)
                            Text("\(item.kind.rawValue) · \(item.stops.count) stops").font(.caption).foregroundStyle(.secondary)
                        }
                    }.disabled(model.running || model.busy || model.recoveryNeeded)
                }
            }
            Section("Android backup interchange") {
                Button("Import backup…") { importingBackup = true }.disabled(model.libraryBusy)
                Button("Export backup…") {
                    Task {
                        if let data = await model.exportBackup() { exportDocument = BackupDocument(data: data); exporting = true }
                    }
                }
                Text("Version 1 Mirage backups preserve places, routes and itinerary metadata. Device pairing and credentials are never exported.").font(.caption)
            }
            Section { Text(model.status).font(.callout) }
        }
    }

    private var setup: some View {
        Form {
            Section("Device output") {
                LabeledContent("Transport", value: DeviceTransport.isAvailable ? "Pinned Remote Pairing bridge" : "Not included in this build")
                Text("This proof sets latitude and longitude only. Speed, course, altitude and accuracy are not supplied by this transport.")
                    .font(.caption)
                Text(model.setupNotice).font(.caption)
                TextField("Remote Pairing endpoint host", text: $model.host).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .disabled(!model.canEditDeviceSetup).onChange(of: model.host) { _, _ in model.setupChanged() }
                TextField("Remote Pairing service port", text: $model.port).keyboardType(.numberPad).disabled(!model.canEditDeviceSetup)
                    .onChange(of: model.port) { _, _ in model.setupChanged() }
                Button(model.pairingImported ? "Replace Remote Pairing file…" : "Import Remote Pairing file…") { importingPairing = true }
                    .disabled(!model.canEditDeviceSetup)
                Text(model.pairingImported ? "Remote Pairing file is stored privately and excluded from backups." : "Select the Remote Pairing plist prepared for this phone. Classic lockdown records are unsupported. Do not paste credentials into messages.")
                    .font(.caption)
            }
            Section("Before the device proof") {
                Text("Target: iPhone 16 Pro Max running iOS 26.6.1. Physical-device compatibility remains unverified.")
                Text("Prepare Developer Mode, this iPhone's Remote Pairing file, the matching cryptex developer image and a working loopback tunnel. Use the Remote Pairing endpoint supplied by that tunnel.")
                Text("Mirage verifies existing Remote Pairing. It does not start PIN pairing, prepare a developer image or install a VPN profile. The bridge must be included in a signed device build; a simulator preview cannot validate device output.")
                Text("The proof pauses its driving loop when backgrounded and requires recovery. Background operation, lock-screen behavior and CarPlay still need physical-device tests.")
            }
            Section("Build status") {
                Text("iPhone 0.1 engineering candidate")
                Text("Full planning, provider routes, hiking, offline dictation and Android feature parity are not yet available in this proof.").font(.caption)
            }
        }
    }

    private func coordinateField(_ title: String, value: Binding<String>, id: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption)
            HStack {
                TextField(title, text: value).textFieldStyle(.roundedBorder)
                    .keyboardType(.numbersAndPunctuation).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .disabled(model.running || model.busy).accessibilityIdentifier(id)
                Button { value.wrappedValue = "" } label: { Image(systemName: "xmark.circle.fill").frame(minWidth: 44, minHeight: 44) }
                    .accessibilityLabel("Clear \(title.lowercased())").disabled(model.running || model.busy)
            }
        }
    }

    private func coordinateRow(_ title: String, point: Coordinate?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption.bold())
            Text(point.map { String(format: "%.6f, %.6f", $0.lat, $0.lng) } ?? "No observation")
                .font(.caption.monospacedDigit()).textSelection(.enabled)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.top, 4)
    }
}
