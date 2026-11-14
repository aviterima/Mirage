import SwiftUI
import MapKit
import Combine
import MirageCore

struct StopChoice: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let address: String
    let point: Coordinate
}

/// Local selection is immediate. Online lookup happens only after Search other places.
@MainActor
final class SavedStopPlanner: ObservableObject {
    typealias SearchProvider = @MainActor (String) async throws -> [StopChoice]
    @Published var query = "" {
        didSet { if query != oldValue { cancelSearch() } }
    }
    @Published private(set) var remoteMatches: [StopChoice] = []
    @Published private(set) var stops: [StopChoice] = []
    @Published private(set) var searching = false
    @Published private(set) var message: String?
    private var generation = 0
    private var task: Task<Void, Never>?
    private var activeSearch: MKLocalSearch?
    private let searchProvider: SearchProvider?

    init(searchProvider: SearchProvider? = nil) { self.searchProvider = searchProvider }

    func savedMatches(_ library: [SavedScenario]) -> [SavedScenario] {
        let words = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return library.filter {
            $0.kind == .place && $0.dest?.isValid == true &&
                (words.isEmpty || ([$0.name, $0.destName, $0.destAddress] + $0.aliases)
                    .joined(separator: " ").localizedCaseInsensitiveContains(words))
        }.sorted {
            if $0.favorite != $1.favorite { return $0.favorite }
            if $0.lastUsedAt != $1.lastUsedAt { return $0.lastUsedAt > $1.lastUsedAt }
            if $0.createdAt != $1.createdAt { return $0.createdAt > $1.createdAt }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    var sequenceName: String {
        let names = stops.prefix(3).map(\.name).joined(separator: " → ")
        return names + (stops.count > 3 ? " → …" : "")
    }

    func add(_ item: SavedScenario) {
        guard item.kind == .place, let point = item.dest, point.isValid else { return }
        append(StopChoice(id: item.id, name: item.name, address: item.destAddress, point: point))
    }

    func append(_ choice: StopChoice) {
        guard choice.point.isValid, stops.count < 1000 else { return }
        // Repeated visits to the same place are distinct stops.
        stops.append(StopChoice(id: UUID().uuidString, name: choice.name, address: choice.address, point: choice.point))
        query = ""
        cancelSearch()
    }

    func remove(_ id: String) { stops.removeAll { $0.id == id } }
    func move(_ id: String, by delta: Int) {
        guard let index = stops.firstIndex(where: { $0.id == id }), stops.indices.contains(index + delta) else { return }
        stops.swapAt(index, index + delta)
    }
    func clear() { stops = []; query = ""; cancelSearch() }

    func cancelSearch() {
        generation += 1
        task?.cancel(); task = nil
        activeSearch?.cancel(); activeSearch = nil
        remoteMatches = []; searching = false; message = nil
    }

    func searchOtherPlaces() {
        let words = query.trimmingCharacters(in: .whitespacesAndNewlines)
        cancelSearch()
        guard words.count >= 2 else { message = "Enter at least two letters and a city to search other places."; return }
        let requestGeneration = generation
        searching = true
        task = Task { [weak self] in
            guard let self, !Task.isCancelled, generation == requestGeneration else { return }
            do {
                let matches: [StopChoice]
                if let searchProvider { matches = try await searchProvider(words) }
                else {
                    let request = MKLocalSearch.Request()
                    request.naturalLanguageQuery = words
                    let search = MKLocalSearch(request: request)
                    activeSearch = search
                    let response = try await search.start()
                    matches = response.mapItems.compactMap { item in
                        let point = Coordinate(lat: item.placemark.coordinate.latitude, lng: item.placemark.coordinate.longitude)
                        guard point.isValid else { return nil }
                        return StopChoice(id: UUID().uuidString, name: item.name ?? "Search result",
                                          address: item.placemark.title ?? "", point: point)
                    }
                }
                guard !Task.isCancelled, generation == requestGeneration else { return }
                remoteMatches = matches.filter { $0.point.isValid }
                searching = false
                message = remoteMatches.isEmpty ? "No online matches. Saved places remain available." : nil
                activeSearch = nil
            } catch {
                guard !Task.isCancelled, generation == requestGeneration else { return }
                searching = false; activeSearch = nil
                message = "Online search is unavailable. Choose a saved place or try again."
            }
        }
    }
}

struct SavedStopPlannerView: View {
    @ObservedObject var model: ProofModel
    @StateObject private var planner = SavedStopPlanner()
    @State private var savedOpen = false
    private var canEdit: Bool { !model.running && !model.busy && !model.stopping }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Choose named locations in order. Playback connects pins with straight lines; road directions and saved stay settings are not applied.")
                    .font(.callout).foregroundStyle(.secondary)
                HStack {
                    TextField("Place, address or saved name", text: $planner.query)
                        .textFieldStyle(.roundedBorder).autocorrectionDisabled()
                        .accessibilityIdentifier("stop-address")
                    Button { planner.query = "" } label: { Image(systemName: "xmark.circle.fill").frame(minWidth: 44, minHeight: 44) }
                        .accessibilityLabel("Clear stop search")
                }.disabled(!canEdit)
                HStack {
                    Button("Saved locations") { savedOpen = true }
                        .buttonStyle(.bordered).accessibilityIdentifier("open-saved-stops")
                    Button("Search other places") { planner.searchOtherPlaces() }
                        .disabled(planner.query.trimmingCharacters(in: .whitespacesAndNewlines).count < 2)
                }.disabled(!canEdit)
                if planner.searching { ProgressView("Searching Apple Maps…") }
                if let message = planner.message { Text(message).font(.caption).foregroundStyle(.secondary) }
                let matches = planner.savedMatches(model.saved)
                if !matches.isEmpty {
                    Text("Saved matches").font(.headline)
                    ForEach(Array(matches.prefix(5)), id: \.id) { item in
                        savedRow(item, prefix: "saved-stop-suggestion")
                    }
                    if matches.count > 5 {
                        Button("See all \(matches.count) saved matches") { savedOpen = true }
                    }
                }
                if !planner.remoteMatches.isEmpty {
                    Text("Other places").font(.headline)
                    ForEach(planner.remoteMatches) { item in
                        Button { planner.append(item) } label: {
                            VStack(alignment: .leading) {
                                Text(item.name)
                                Text(item.address).font(.caption).foregroundStyle(.secondary)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.disabled(!canEdit)
                    }
                }
                Divider()
                Text("Sequence · \(planner.stops.count) stops").font(.headline).accessibilityIdentifier("sequence-count")
                if planner.stops.isEmpty { Text("Select a saved or searched location to add the first stop.").foregroundStyle(.secondary) }
                ForEach(Array(planner.stops.enumerated()), id: \.element.id) { index, stop in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(index + 1). \(stop.name)").font(.headline)
                        if !stop.address.isEmpty { Text(stop.address).font(.caption).foregroundStyle(.secondary) }
                        HStack {
                            Button("Up") { planner.move(stop.id, by: -1) }.disabled(index == 0)
                                .accessibilityLabel("Move \(stop.name) up")
                            Button("Down") { planner.move(stop.id, by: 1) }.disabled(index == planner.stops.count - 1)
                                .accessibilityLabel("Move \(stop.name) down")
                            Button("Remove", role: .destructive) { planner.remove(stop.id) }
                                .accessibilityLabel("Remove \(stop.name)")
                        }.buttonStyle(.bordered).disabled(!canEdit)
                    }.accessibilityIdentifier("planned-stop-\(index)")
                }
                if planner.stops.count >= 2 {
                    Map {
                        MapPolyline(coordinates: planner.stops.map { CLLocationCoordinate2D(latitude: $0.point.lat, longitude: $0.point.lng) })
                            .stroke(.teal, lineWidth: 3)
                        ForEach(Array(planner.stops.enumerated()), id: \.element.id) { index, stop in
                            Marker("\(index + 1). \(stop.name)", coordinate: CLLocationCoordinate2D(latitude: stop.point.lat, longitude: stop.point.lng))
                        }
                    }.frame(height: 200).clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel("Straight-line sequence preview")
                }
                TextField("Total playback seconds", text: $model.duration).textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad).disabled(!canEdit).accessibilityIdentifier("sequence-duration")
                Button(model.mode == .preview ? "Play sequence in preview" : "Play sequence on iPhone") {
                    Task { await model.playStopSequence(planner.stops.map(\.point), name: planner.sequenceName) }
                }.buttonStyle(.borderedProminent).disabled(!model.canRun || planner.stops.count < 2)
                    .accessibilityIdentifier("play-stop-sequence")
                Text(model.mode == .preview ? "Preview does not change iPhone location." : "Connect iPhone output in Device proof before playback.")
                    .font(.caption).foregroundStyle(.secondary)
                Text(model.playbackStatus).font(.caption).accessibilityIdentifier("sequence-playback-status")
                Text(model.status).font(.caption)
                Button("Clear sequence", role: .destructive) { planner.clear() }.disabled(!canEdit || planner.stops.isEmpty)
            }.padding()
        }.scrollDismissesKeyboard(.interactively)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
            }
        }
        .sheet(isPresented: $savedOpen) {
            NavigationStack {
                List {
                    HStack {
                        TextField("Filter names, aliases or addresses", text: $planner.query)
                            .accessibilityIdentifier("saved-stop-filter").autocorrectionDisabled()
                        Button { planner.query = "" } label: { Image(systemName: "xmark.circle.fill").frame(minWidth: 44, minHeight: 44) }
                            .accessibilityLabel("Clear saved search")
                    }
                    let matches = planner.savedMatches(model.saved)
                    if matches.isEmpty {
                        Text(model.saved.contains { $0.kind == .place } ? "No saved locations match." : "Save a place in Saved to use it here.")
                    }
                    ForEach(matches, id: \.id) { item in savedRow(item, prefix: "saved-stop-picker") }
                }.navigationTitle("Saved locations")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { savedOpen = false } } }
            }
        }
    }

    private func savedRow(_ item: SavedScenario, prefix: String) -> some View {
        Button {
            planner.add(item); savedOpen = false
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text((item.favorite ? "★ " : "") + item.name)
                Text(item.destAddress.isEmpty ? item.destName : item.destAddress).font(.caption).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }.disabled(!canEdit).accessibilityLabel("Add saved location \(item.name)")
            .accessibilityIdentifier("\(prefix)-\(item.name)")
    }
}
