import Foundation
import CryptoKit
import XCTest
@testable import Mirage

/// All key material is generated from synthetic fixture seeds. No files or device services are used.
final class RemotePairingValidationTests: XCTestCase {
    func testAcceptsMatchingSigningKeysInXMLAndBinaryPlists() throws {
        let record = try validRecord()
        for format in [PropertyListSerialization.PropertyListFormat.xml, .binary] {
            let data = try PropertyListSerialization.data(fromPropertyList: record, format: format, options: 0)
            XCTAssertNoThrow(try PrivateFiles.validateRemotePairing(data))
        }
        var withAltIRK = record
        withAltIRK["alt_irk"] = Data(repeating: 0x5a, count: 16)
        XCTAssertNoThrow(try PrivateFiles.validateRemotePairing(encode(withAltIRK)))
    }

    func testClassicLockdownRecordIsRejectedExplicitly() throws {
        let classic: [String: Any] = ["HostID": "synthetic-host", "SystemBUID": "synthetic-system",
                                      "HostCertificate": Data([1]), "HostPrivateKey": Data([2])]
        assertRejected(try encode(classic), as: .classicRecord)
        var mixed = try validRecord()
        mixed["HostPrivateKey"] = Data([2])
        assertRejected(try encode(mixed), as: .classicRecord)
    }

    func testSigningKeysMustBeDataWithExactLengths() throws {
        let invalidValues: [Any] = [Data(), Data(repeating: 1, count: 31), Data(repeating: 1, count: 33), "not-key-data"]
        for field in ["public_key", "private_key"] {
            for invalid in invalidValues {
                var record = try validRecord()
                record[field] = invalid
                assertRejected(try encode(record), as: .invalidKeys)
            }
            var missing = try validRecord()
            missing.removeValue(forKey: field)
            assertRejected(try encode(missing), as: .invalidKeys)
        }
    }

    func testMismatchedPublicAndPrivateSigningKeysAreRejected() throws {
        var record = try validRecord()
        let other = try Curve25519.Signing.PrivateKey(rawRepresentation: Data(repeating: 0x39, count: 32))
        record["public_key"] = other.publicKey.rawRepresentation
        assertRejected(try encode(record), as: .keyMismatch)
    }

    func testIdentifierIsNonemptyAndBoundedByUTF8Bytes() throws {
        let invalidValues: [Any] = ["", " \n\t", String(repeating: "a", count: 257), String(repeating: "é", count: 129), 42]
        for invalid in invalidValues {
            var record = try validRecord()
            record["identifier"] = invalid
            assertRejected(try encode(record), as: .invalidIdentifier)
        }
        var exactLimit = try validRecord()
        exactLimit["identifier"] = String(repeating: "é", count: 128)
        XCTAssertNoThrow(try PrivateFiles.validateRemotePairing(encode(exactLimit)))
    }

    func testOptionalAltIRKRequiresSixteenBytes() throws {
        let invalidValues: [Any] = [Data(), Data(repeating: 1, count: 15), Data(repeating: 1, count: 17), "not-key-data"]
        for invalid in invalidValues {
            var record = try validRecord()
            record["alt_irk"] = invalid
            assertRejected(try encode(record), as: .invalidAltIRK)
        }
    }

    func testMalformedRootAndOversizedInputAreRejected() throws {
        assertRejected(Data(), as: .invalidSize)
        assertRejected(Data(repeating: 0, count: 1024 * 1024 + 1), as: .invalidSize)
        assertRejected(Data("not a plist".utf8), as: .malformed)
        let array = try PropertyListSerialization.data(fromPropertyList: ["wrong root"], format: .binary, options: 0)
        assertRejected(array, as: .malformed)
    }

    func testNativeRemotePairingFailuresHaveDistinctActionableErrors() {
        XCTAssertThrowsError(try DeviceTransportError.check(10)) { error in
            guard let typed = error as? DeviceTransportError, case .pairingRejected = typed else {
                return XCTFail("Expected Remote Pairing verification rejection")
            }
            XCTAssertTrue(typed.localizedDescription.contains("does not start PIN pairing"))
        }
        XCTAssertThrowsError(try DeviceTransportError.check(11)) { error in
            guard let typed = error as? DeviceTransportError, case .developerServiceUnavailable = typed else {
                return XCTFail("Expected missing developer-service guidance")
            }
            XCTAssertTrue(typed.localizedDescription.contains("cryptex developer image"))
        }
    }

    private func validRecord() throws -> [String: Any] {
        let privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: Data((0..<32).map { UInt8($0) }))
        return ["public_key": privateKey.publicKey.rawRepresentation,
                "private_key": privateKey.rawRepresentation,
                "identifier": "synthetic-remote-pairing-fixture"]
    }

    private func encode(_ record: [String: Any]) throws -> Data {
        try PropertyListSerialization.data(fromPropertyList: record, format: .binary, options: 0)
    }

    private func assertRejected(_ bytes: Data, as expected: RemotePairingFileError,
                                file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try PrivateFiles.validateRemotePairing(bytes), file: file, line: line) { error in
            XCTAssertEqual(error as? RemotePairingFileError, expected, file: file, line: line)
        }
    }
}
