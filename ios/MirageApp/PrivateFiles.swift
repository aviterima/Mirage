import Foundation
import CryptoKit
import UniformTypeIdentifiers
import SwiftUI

enum PrivateFiles {
    static func directory() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true).appendingPathComponent("Mirage", isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    static func pairingURL() throws -> URL { try directory().appendingPathComponent("remote-pairing.plist") }

    /// Validate credentials without writing files, starting pairing or contacting a device.
    /// This Remote Pairing format is separate from classic lockdown pairing records.
    static func validateRemotePairing(_ bytes: Data) throws {
        guard !bytes.isEmpty, bytes.count <= 1024 * 1024 else { throw RemotePairingFileError.invalidSize }
        let value: Any
        do { value = try PropertyListSerialization.propertyList(from: bytes, format: nil) }
        catch { throw RemotePairingFileError.malformed }
        guard let object = value as? [String: Any] else { throw RemotePairingFileError.malformed }
        if ["HostID", "SystemBUID", "HostCertificate", "HostPrivateKey"].contains(where: { object[$0] != nil }) {
            throw RemotePairingFileError.classicRecord
        }
        guard let publicKey = object["public_key"] as? Data, publicKey.count == 32,
              let privateKey = object["private_key"] as? Data, privateKey.count == 32 else {
            throw RemotePairingFileError.invalidKeys
        }
        guard let identifier = object["identifier"] as? String,
              !identifier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              identifier.utf8.count <= 256 else { throw RemotePairingFileError.invalidIdentifier }
        if let value = object["alt_irk"] {
            guard let key = value as? Data, key.count == 16 else { throw RemotePairingFileError.invalidAltIRK }
        }
        do {
            let key = try Curve25519.Signing.PrivateKey(rawRepresentation: privateKey)
            guard key.publicKey.rawRepresentation == publicKey else { throw RemotePairingFileError.keyMismatch }
        } catch let error as RemotePairingFileError { throw error }
        catch { throw RemotePairingFileError.invalidKeys }
    }

    static func importPairing(from url: URL) throws {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile == true, let size = values.fileSize, size > 0, size <= 1024 * 1024 else {
            throw RemotePairingFileError.invalidSize
        }
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var bytes = try file.read(upToCount: 1024 * 1024 + 1) ?? Data()
        defer { bytes.resetBytes(in: bytes.startIndex..<bytes.endIndex) }
        try validateRemotePairing(bytes)
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

enum RemotePairingFileError: LocalizedError, Equatable {
    case invalidSize, malformed, classicRecord, invalidKeys, invalidIdentifier, invalidAltIRK, keyMismatch

    var errorDescription: String? {
        switch self {
        case .invalidSize:
            "Select a nonempty Remote Pairing plist no larger than 1 MiB."
        case .malformed:
            "The Remote Pairing file is not a readable plist dictionary."
        case .classicRecord:
            "This is a classic lockdown pairing record. Import the Remote Pairing file prepared for this iPhone instead."
        case .invalidKeys:
            "The Remote Pairing file must contain 32-byte public_key and private_key data fields."
        case .invalidIdentifier:
            "The Remote Pairing file needs a nonempty identifier of at most 256 UTF-8 bytes."
        case .invalidAltIRK:
            "The Remote Pairing file's optional alt_irk must contain 16 bytes of data."
        case .keyMismatch:
            "The Remote Pairing signing keys do not match. Import the original file prepared for this iPhone."
        }
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
