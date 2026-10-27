import Foundation
import UniformTypeIdentifiers
import SwiftUI

enum PrivateFiles {
    static func directory() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true).appendingPathComponent("Mirage", isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    static func pairingURL() throws -> URL { try directory().appendingPathComponent("device-pairing.plist") }

    static func importPairing(from url: URL) throws {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile == true, let size = values.fileSize, size > 0, size <= 1024 * 1024 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let bytes = try file.read(upToCount: 1024 * 1024 + 1) ?? Data()
        guard !bytes.isEmpty, bytes.count <= 1024 * 1024 else { throw CocoaError(.fileReadCorruptFile) }
        guard let object = try PropertyListSerialization.propertyList(from: bytes, format: nil) as? [String: Any],
              object["HostID"] is String, object["SystemBUID"] is String,
              object["HostCertificate"] is Data, object["HostPrivateKey"] is Data else {
            throw CocoaError(.fileReadCorruptFile)
        }
        var destination = try pairingURL()
        // Protect the directory before writing so even the atomic temporary file is excluded from backup.
        var directory = destination.deletingLastPathComponent()
        var attributes = URLResourceValues()
        attributes.isExcludedFromBackup = true
        try directory.setResourceValues(attributes)
        try bytes.write(to: destination, options: [.atomic, .completeFileProtection])
        try destination.setResourceValues(attributes)
    }
}

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
