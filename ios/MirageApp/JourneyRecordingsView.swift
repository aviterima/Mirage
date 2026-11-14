import SwiftUI
import MirageCore

@MainActor
struct JourneyRecordingsView: View {
    @ObservedObject var model: ProofModel
    @ObservedObject var observer: LocationObserver
    @ObservedObject private var recordings: JourneyRecordingsModel
    @State private var renameID: String?
    @State private var name = ""
    @State private var deleteItem: JourneySummary?
    @State private var replayItem: JourneySummary?
    @State private var speed = 1.0

    init(model: ProofModel, observer: LocationObserver) {
        self.model = model; self.observer = observer
        self.recordings = model.recordings
    }

    var body: some View {
        List {
            Section {
                Text(recordings.status).accessibilityIdentifier("recording-status")
                Text("Recordings stay on this iPhone and are excluded from automatic backup.")
                    .font(.caption).foregroundStyle(.secondary)
                if recordings.busy { ProgressView("Saving recording…") }
            }
            if let active = recordings.active {
                Section("Recording now") {
                    Text(active.name).font(.headline)
                    Text("\(active.sampleCount) locations · \(duration(active.duration))")
                    if active.source == .real { Text(observer.status).font(.caption) }
                    Button("Stop and save recording") { recordings.finish() }
                        .disabled(recordings.busy).accessibilityIdentifier("stop-recording")
                }
            } else if !recordings.busy {
                Section("Start recording") {
                    Button("Record real journey") {
                        recordings.start(.real)
                        if recordings.realActive { observer.startRealRecording() }
                    }.disabled(model.running || model.busy || model.stopping || model.recoveryNeeded || recordings.recording)
                        .accessibilityIdentifier("record-real-journey")
                    Text("Start while this app is open. Real recording continues with the location indicator when you switch apps or lock the screen.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button(model.mode == .preview ? "Record preview positions" : "Record emulated day") {
                        recordings.start(model.mode == .preview ? .preview : .emulated)
                    }.disabled(recordings.recording).accessibilityIdentifier("record-emulated-day")
                    Text(model.mode == .preview ? "Preview recordings are labeled separately; they do not represent changed iPhone location." :
                        "Captures acknowledged output across sessions. iPhone output stops or becomes unverified in the background; those intervals are gaps.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Section("Saved recordings") {
                if recordings.library.filter({ $0.id != recordings.active?.id }).isEmpty {
                    Text("Your recorded journeys will appear here with editable suggested names.").foregroundStyle(.secondary)
                }
                ForEach(recordings.library.filter { $0.id != recordings.active?.id }, id: \.id) { item in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(item.name).font(.headline)
                        Text("\(sourceName(item.source)) · \(duration(item.duration)) · \(item.sampleCount) locations").font(.caption)
                        if item.state == .interrupted { Text("Interrupted recording · captured locations recovered").font(.caption) }
                        if item.gapCount > 0 { Text("\(item.gapCount) recorded gaps").font(.caption) }
                        HStack {
                            Button("Replay") { speed = 1; replayItem = item }
                                .disabled(!model.canReplayRecording || item.sampleCount == 0)
                                .accessibilityIdentifier("replay-recording-\(item.id)")
                            Button("Rename") { name = item.name; renameID = item.id }
                                .disabled(recordings.busy).accessibilityIdentifier("rename-recording-\(item.id)")
                            Button("Delete", role: .destructive) { deleteItem = item }.disabled(recordings.busy)
                        }.buttonStyle(.bordered)
                    }.padding(.vertical, 4)
                }
                if !model.connected { Text("Connect the selected output in Device proof before replaying.").font(.caption) }
                if model.running { Text("Stop the current output before replaying another recording.").font(.caption) }
            }
        }
        .navigationTitle("Recordings")
        .task {
            recordings.onRealRecordingEnded = { observer.endRealRecording() }
            observer.onRecordingUnavailable = {
                recordings.finish()
                recordings.status = "Location permission is unavailable. Captured locations will be saved."
            }
            await recordings.reload()
        }
        .alert("Name recording", isPresented: Binding(get: { renameID != nil }, set: { if !$0 { renameID = nil } })) {
            TextField("Recording name", text: $name).accessibilityIdentifier("recording-name")
            Button("Save name") {
                if let id = renameID { Task { await recordings.rename(id, to: name) } }
                renameID = nil
            }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || name.count > 160)
            Button("Cancel", role: .cancel) { renameID = nil }
        }
        .confirmationDialog("Delete this recording and its locations?", isPresented: Binding(
            get: { deleteItem != nil }, set: { if !$0 { deleteItem = nil } }), titleVisibility: .visible) {
            Button("Delete recording", role: .destructive) {
                if let item = deleteItem { Task { await recordings.delete(item.id) } }
                deleteItem = nil
            }
        }
        .sheet(item: Binding<JourneyReplaySelection?>(
            get: { replayItem.map { JourneyReplaySelection(summary: $0) } },
            set: { replayItem = $0?.summary })) { selection in
                NavigationStack {
                    Form {
                        Text(selection.summary.name).font(.headline)
                        Text("Replay starts at the first recorded location. Missing sections hold the previous position until samples resume.")
                        Picker("Replay speed", selection: $speed) {
                            ForEach([1.0, 2.0, 5.0, 10.0], id: \.self) { Text("\(Int($0))×").tag($0) }
                        }.pickerStyle(.segmented)
                        Text(model.mode == .preview ? "Preview only; iPhone location will not change." : "Uses the connected iPhone output. Device setup and recovery requirements still apply.")
                        Button("Start replay") {
                            let id = selection.summary.id
                            replayItem = nil
                            Task { await model.replayRecording(id, speed: speed) }
                        }.disabled(!model.canReplayRecording).accessibilityIdentifier("start-recording-replay")
                    }.navigationTitle("Replay recording")
                        .toolbar { Button("Cancel") { replayItem = nil } }
                }
            }
    }

    private func sourceName(_ value: JourneySource) -> String {
        switch value { case .real: return "Real journey"; case .emulated: return "Emulated day"; case .preview: return "Preview" }
    }
    private func duration(_ seconds: Double) -> String {
        let minutes = Int(seconds / 60)
        return minutes >= 60 ? "\(minutes / 60)h \(minutes % 60)m" : "\(minutes)m"
    }
}

private struct JourneyReplaySelection: Identifiable {
    let summary: JourneySummary
    var id: String { summary.id }
}
