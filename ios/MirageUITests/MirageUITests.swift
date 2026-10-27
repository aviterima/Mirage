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

    private func capture(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
