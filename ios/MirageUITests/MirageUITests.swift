import XCTest

final class MirageUITests: XCTestCase {
    func testPreviewRequiresConnectAndNeverClaimsDeviceOutput() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["iPhone engineering proof"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["set-test-point"].isEnabled)
        app.segmentedControls.buttons["Preview only"].tap()
        app.buttons["connect-output"].tap()
        XCTAssertTrue(app.buttons["Connected"].waitForExistence(timeout: 5))
        app.buttons["set-test-point"].tap()
        XCTAssertTrue(app.staticTexts["Preview only. No device output."].waitForExistence(timeout: 5))
        capture(app, name: "Explicit preview output")
        app.buttons["stop-output"].tap()
        XCTAssertTrue(app.staticTexts["Preview stopped. No device output was changed."].waitForExistence(timeout: 5))
        app.segmentedControls.buttons["iPhone output"].tap()
        app.buttons["connect-output"].tap()
        XCTAssertTrue(app.staticTexts["This build supports preview only. Use a device-enabled build for iPhone output."].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["set-test-point"].isEnabled)
        app.tabBars.buttons["Saved"].tap()
        XCTAssertTrue(app.buttons["Import backup…"].waitForExistence(timeout: 5))
        capture(app, name: "Saved library and backup controls")
        app.tabBars.buttons["Setup"].tap()
        XCTAssertTrue(app.buttons["Import Remote Pairing file…"].waitForExistence(timeout: 5))
        capture(app, name: "Remote Pairing setup")
    }

    func testInvalidCoordinatesBlockOutput() {
        let app = XCUIApplication()
        app.launch()
        app.segmentedControls.buttons["Preview only"].tap()
        app.buttons["connect-output"].tap()
        let latitude = app.textFields["latitude"]
        app.buttons["Clear latitude"].tap()
        latitude.tap()
        latitude.typeText("999")
        capture(app, name: "Coordinate entry with keyboard")
        app.buttons["Done"].tap()
        XCTAssertFalse(app.buttons["set-test-point"].isEnabled)
        XCTAssertFalse(app.buttons["play-synthetic-route"].isEnabled)
    }

    func testSavedStopPickerSharesTypedFilterAndSelectionDoesNotPlay() {
        let app = XCUIApplication()
        app.launch()
        let suffix = String(UUID().uuidString.prefix(6))
        let home = "Home \(suffix)"
        let office = "Office \(suffix)"
        app.tabBars.buttons["Saved"].tap()
        for name in [home, office] {
            app.buttons["Clear place name"].tap()
            let field = app.textFields["saved-place-name"]
            field.tap(); field.typeText(name)
            app.buttons["Done"].tap()
            app.buttons["save-current-place"].tap()
            XCTAssertTrue(app.buttons["library-item-\(name)"].waitForExistence(timeout: 5))
        }
        app.tabBars.buttons["Plan stops"].tap()
        let query = app.textFields["stop-address"]
        XCTAssertTrue(query.waitForExistence(timeout: 5))
        query.tap(); query.typeText("H")
        XCTAssertTrue(app.buttons["saved-stop-suggestion-\(home)"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["saved-stop-suggestion-\(office)"].exists)
        app.buttons["open-saved-stops"].tap()
        let filter = app.textFields["saved-stop-filter"]
        XCTAssertTrue(filter.waitForExistence(timeout: 5))
        XCTAssertEqual(filter.value as? String, "H")
        XCTAssertTrue(app.buttons["saved-stop-picker-\(home)"].exists)
        XCTAssertFalse(app.buttons["saved-stop-picker-\(office)"].exists)
        capture(app, name: "Saved stops filtered from the address")
        app.buttons["Clear saved search"].tap()
        XCTAssertTrue(app.buttons["saved-stop-picker-\(office)"].exists)
        app.buttons["saved-stop-picker-\(home)"].tap()
        XCTAssertTrue(app.staticTexts["sequence-count"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["sequence-count"].label, "Sequence · 1 stops")
        query.tap(); query.typeText(office)
        app.buttons["saved-stop-suggestion-\(office)"].tap()
        XCTAssertEqual(app.staticTexts["sequence-count"].label, "Sequence · 2 stops")
        XCTAssertFalse(app.buttons["play-stop-sequence"].isEnabled)
        XCTAssertEqual(app.staticTexts["sequence-playback-status"].label, "Stopped")
        capture(app, name: "Two named stops await explicit playback")
    }

    func testPreviewZoomUsesSeparateWordingAndStopsWithPlayback() {
        let app = XCUIApplication()
        app.launch()
        app.segmentedControls.buttons["Preview only"].tap()
        app.buttons["connect-output"].tap()
        XCTAssertTrue(app.buttons["Connected"].waitForExistence(timeout: 5))
        app.buttons["set-test-point"].tap()
        XCTAssertTrue(app.staticTexts["Preview only. No device output."].waitForExistence(timeout: 5))
        let zoom = app.buttons["zoom-emulated-location"]
        for _ in 0..<8 where !zoom.isHittable { app.swipeUp() }
        XCTAssertTrue(zoom.isHittable)
        XCTAssertEqual(zoom.label, "Zoom to preview position")
        XCTAssertTrue(zoom.isEnabled)
        zoom.tap()
        capture(app, name: "Zoom centered on separate preview position")
        let stop = app.buttons["stop-output"]
        for _ in 0..<8 where !stop.isHittable { app.swipeDown() }
        stop.tap()
        XCTAssertTrue(app.staticTexts["Preview stopped. No device output was changed."].waitForExistence(timeout: 5))
        for _ in 0..<8 where !zoom.isHittable { app.swipeUp() }
        XCTAssertFalse(zoom.isEnabled)
    }

    private func capture(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
