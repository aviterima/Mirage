import Foundation

/// Local template persistence, serialized through an actor. Import never starts playback.
/// The whole candidate is validated and encoded before an atomic replacement of the old file.
public actor ScenarioStore {
    public let fileURL: URL
    private let atomicWriter: @Sendable (Data, URL) throws -> Void

    public init(fileURL: URL) {
        self.fileURL = fileURL
        atomicWriter = { data, url in try data.write(to: url, options: .atomic) }
    }

    /// Fault injection is internal so production callers cannot replace the atomic writer.
    init(fileURL: URL, atomicWriter: @escaping @Sendable (Data, URL) throws -> Void) {
        self.fileURL = fileURL
        self.atomicWriter = atomicWriter
    }

    public func load() throws -> [SavedScenario] {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return [] }
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        // Read at most the limit plus one byte even if an external process replaced the file.
        let data = try handle.read(upToCount: BackupCodec.maxBytes + 1) ?? Data()
        return try BackupCodec.decode(data)
    }

    public func save(_ items: [SavedScenario]) throws {
        let data = try BackupCodec.encode(items)
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try atomicWriter(data, fileURL)
    }

    /// Read/modify/write stays inside this actor without suspension. A stale UI cache
    /// cannot erase another saved item, and a corrupt existing file is never replaced.
    @discardableResult
    public func append(_ item: SavedScenario) throws -> [SavedScenario] {
        let items = try load() + [item]
        try save(items)
        return items
    }

    @discardableResult
    public func importBackup(_ data: Data) throws -> [SavedScenario] {
        let items = try BackupCodec.decode(data)
        try save(items)
        return items
    }

    public func exportBackup() throws -> Data { try BackupCodec.encode(load()) }
}
