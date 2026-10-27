import Foundation
import MirageCore

#if MIRAGE_DEVICE_BRIDGE
import MirageDeviceBridge
#endif

/// A Remote Pairing developer-service transport. The ordinary simulator target builds
/// without the native library and rejects every device operation explicitly.
final class DeviceTransport: LocationOutput, @unchecked Sendable {
    let capabilities = LocationOutputCapabilities(
        name: "iPhone Remote Pairing developer services (experimental)", mode: .device
    )

    static var isAvailable: Bool {
        #if MIRAGE_DEVICE_BRIDGE
        true
        #else
        false
        #endif
    }

    private let pairingRecordURL: URL
    private let host: String
    private let port: UInt16
    private let queue = DispatchQueue(label: "com.mirage.device-requests", qos: .userInitiated)

    #if MIRAGE_DEVICE_BRIDGE
    // Accessed only on queue. The Rust worker, not this queue, owns all idevice
    // handles and guarantees their OS-thread affinity.
    private var nativeHandle: OpaquePointer?
    #endif

    init(pairingRecordURL: URL, host: String, port: UInt16) {
        self.pairingRecordURL = pairingRecordURL
        self.host = host
        self.port = port
    }

    func connect() async throws {
        try await perform {
            #if MIRAGE_DEVICE_BRIDGE
            guard self.pairingRecordURL.isFileURL, self.port > 0 else {
                throw DeviceTransportError.invalidSetup
            }
            if self.nativeHandle == nil { self.nativeHandle = mirage_device_create() }
            guard let handle = self.nativeHandle else { throw DeviceTransportError.workerUnavailable }
            let file: FileHandle
            do { file = try FileHandle(forReadingFrom: self.pairingRecordURL) }
            catch { throw DeviceTransportError.pairingUnavailable }
            defer { try? file.close() }
            var pairing: Data
            do { pairing = try file.read(upToCount: 1_048_577) ?? Data() }
            catch { throw DeviceTransportError.pairingUnavailable }
            defer { pairing.resetBytes(in: pairing.startIndex..<pairing.endIndex) }
            guard !pairing.isEmpty, pairing.count <= 1_048_576 else {
                throw DeviceTransportError.invalidPairing
            }
            try PrivateFiles.validateRemotePairing(pairing)
            let status = self.host.withCString { hostPointer in
                pairing.withUnsafeBytes { bytes in
                    mirage_device_connect(handle, hostPointer, self.port,
                        bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count)
                }
            }
            try DeviceTransportError.check(status)
            #else
            throw DeviceTransportError.notBuilt
            #endif
        }
    }

    func send(_ coordinate: Coordinate) async throws {
        try await perform {
            #if MIRAGE_DEVICE_BRIDGE
            guard let handle = self.nativeHandle else { throw DeviceTransportError.notConnected }
            try DeviceTransportError.check(mirage_device_set(handle, coordinate.lat, coordinate.lng))
            #else
            throw DeviceTransportError.notBuilt
            #endif
        }
    }

    func clear() async throws {
        try await perform {
            #if MIRAGE_DEVICE_BRIDGE
            guard let handle = self.nativeHandle else { throw DeviceTransportError.notConnected }
            try DeviceTransportError.check(mirage_device_clear(handle))
            #else
            throw DeviceTransportError.notBuilt
            #endif
        }
    }

    private func perform(_ operation: @escaping @Sendable () throws -> Void) async throws {
        // Cancellation does not abandon a command already submitted to a device.
        // LocationSession owns stale-result handling and must still await clear.
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async {
                do { try operation(); continuation.resume() }
                catch { continuation.resume(throwing: error) }
            }
        }
    }

    deinit {
        #if MIRAGE_DEVICE_BRIDGE
        if let nativeHandle {
            // No caller remains when deinit runs. Keep shutdown off the UI thread.
            let owned = NativeHandleForShutdown(nativeHandle)
            queue.async { mirage_device_destroy(owned.raw) }
        }
        #endif
    }
}

#if MIRAGE_DEVICE_BRIDGE
private final class NativeHandleForShutdown: @unchecked Sendable {
    let raw: OpaquePointer
    init(_ raw: OpaquePointer) { self.raw = raw }
}
#endif

enum DeviceTransportError: LocalizedError {
    case notBuilt, invalidSetup, pairingUnavailable, invalidPairing, notConnected
    case connectionFailed, writeFailed, clearFailed, timedOut, alreadyConnected, workerUnavailable
    case pairingRejected, developerServiceUnavailable

    var errorDescription: String? {
        switch self {
        case .notBuilt:
            "This build does not include device output. Build the pinned device bridge and the Device configuration on a Mac."
        case .invalidSetup:
            "Device setup is invalid. Use the Remote Pairing service's numeric tunnel IP address and port, and a local Remote Pairing file."
        case .pairingUnavailable:
            "The Remote Pairing file could not be read. Unlock the phone and import its Remote Pairing file again."
        case .invalidPairing:
            "The Remote Pairing file is invalid or exceeds 1 MiB. Import the file prepared for this iPhone; classic lockdown records are unsupported."
        case .notConnected:
            "Developer services are disconnected. Connect before sending a location; recovery may still be needed."
        case .connectionFailed:
            "Remote Pairing could not connect. Check the Remote Pairing endpoint and loopback tunnel, then verify Developer Mode and the prepared cryptex developer image."
        case .writeFailed:
            "The device did not acknowledge the location. Output is uncertain; Stop must attempt recovery."
        case .clearFailed:
            "The device did not acknowledge Stop. Reconnect the tunnel and retry Stop; real location is not yet verified."
        case .timedOut:
            "Developer services timed out. Output may remain simulated; restore the tunnel and retry Stop."
        case .alreadyConnected:
            "This transport is already connected. Stop before replacing the device connection."
        case .workerUnavailable:
            "The native device worker is unavailable. Restart the app and use Stop to attempt recovery."
        case .pairingRejected:
            "Remote Pairing verification was rejected. Import the Remote Pairing file trusted by this iPhone. Mirage verifies existing pairing and does not start PIN pairing."
        case .developerServiceUnavailable:
            "The Remote Pairing tunnel connected, but the location developer service is unavailable. Prepare the matching cryptex developer image and check Developer Mode before retrying."
        }
    }

    static func check(_ status: Int32) throws {
        switch status {
        case 0: return
        case 1: throw invalidSetup
        case 2: throw invalidPairing
        case 3: throw notConnected
        case 4: throw connectionFailed
        case 5: throw writeFailed
        case 6: throw clearFailed
        case 7: throw timedOut
        case 8: throw alreadyConnected
        case 10: throw pairingRejected
        case 11: throw developerServiceUnavailable
        default: throw workerUnavailable
        }
    }
}
