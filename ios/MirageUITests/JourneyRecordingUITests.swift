import XCTest

final class JourneyRecordingUITests: XCTestCase {
    func testPreviewRecordingSourceSaveRenameAndExplicitReplay() {
        let app = XCUIApplication()
        app.launch()
        app.segmentedControls.buttons["Preview only"].tap()
        app.buttons["connect-output"].tap()
        XCTAssertTrue(app.buttons["Connected"].waitForExistence(timeout: 5))
        app.tabBars.buttons["Recordings"].tap()
        let record = app.buttons["record-emulated-day"]
        XCTAssertTrue(record.waitForExistence(timeout: 5))
        XCTAssertEqual(record.label, "Record preview positions")
        XCTAssertFalse(app.buttons["record-real-journey"].isEnabled, "Connected output must be cleared before real recording")
        XCTAssertTrue(app.staticTexts["Use Stop / Clear in Device proof and wait for real-location recovery before recording a real journey."].exists)
        XCTAssertFalse(app.buttons["stop-recording"].exists)
        record.tap()
        XCTAssertTrue(app.buttons["stop-recording"].waitForExistence(timeout: 5))
        app.tabBars.buttons["Device proof"].tap()
        app.buttons["set-test-point"].tap()
        XCTAssertTrue(app.staticTexts["Preview only. No device output."].waitForExistence(timeout: 5))
        app.tabBars.buttons["Recordings"].tap()
        let status = app.staticTexts["recording-status"]
        let received = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label MATCHES %@", "[1-9][0-9]* locations recorded"), object: status)
        XCTAssertEqual(XCTWaiter.wait(for: [received], timeout: 5), .completed)
        capture(app, "Recording preview positions with visible stop")
        app.buttons["stop-recording"].tap()
        XCTAssertTrue(app.staticTexts["Recording saved. You can rename it or replay it."].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["record-real-journey"].isEnabled, "Running preview cannot be mixed into real recording")
        capture(app, "Saved preview and real-recording guard")
        app.tabBars.buttons["Device proof"].tap()
        app.buttons["stop-output"].tap()
        XCTAssertTrue(app.staticTexts["Preview stopped. No device output was changed."].waitForExistence(timeout: 5))
        app.buttons["connect-output"].tap()
        XCTAssertTrue(app.buttons["Connected"].waitForExistence(timeout: 5))
        app.tabBars.buttons["Recordings"].tap()
        let rename = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "rename-recording-")).firstMatch
        scrollTo(rename, in: app)
        let id = String(rename.identifier.dropFirst("rename-recording-".count))
        rename.tap()
        let field = app.alerts.textFields["recording-name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        let suffix = " UI" + String(UUID().uuidString.prefix(6))
        field.typeText(suffix)
        let editedName = field.value as? String
        XCTAssertTrue(editedName?.contains(suffix) == true)
        app.alerts.buttons["Save name"].tap()
        if let editedName { XCTAssertTrue(app.staticTexts[editedName].waitForExistence(timeout: 5)) }
        capture(app, "Editable recording name saved")
        let replay = app.buttons["replay-recording-\(id)"]
        scrollTo(replay, in: app)
        XCTAssertTrue(replay.isEnabled)
        replay.tap()
        let start = app.buttons["start-recording-replay"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Preview only; iPhone location will not change."].exists)
        capture(app, "Explicit preview replay confirmation")
        start.tap()
        app.tabBars.buttons["Device proof"].tap()
        XCTAssertTrue(app.staticTexts["Preview only. No device output."].waitForExistence(timeout: 5))
        app.buttons["stop-output"].tap()
        XCTAssertTrue(app.staticTexts["Preview stopped. No device output was changed."].waitForExistence(timeout: 5))
    }

    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<6 where !element.isHittable { app.swipeUp() }
        XCTAssertTrue(element.isHittable)
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
