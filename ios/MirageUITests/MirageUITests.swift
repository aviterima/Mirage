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
        let capture = XCTAttachment(screenshot: app.screenshot())
        capture.name = "Explicit preview output"
        capture.lifetime = .keepAlways
        add(capture)
        app.buttons["stop-output"].tap()
        XCTAssertTrue(app.staticTexts["Preview stopped. No device output was changed."].waitForExistence(timeout: 5))
        app.segmentedControls.buttons["iPhone output"].tap()
        app.buttons["connect-output"].tap()
        XCTAssertTrue(app.staticTexts["This build supports preview only. Use a device-enabled build for iPhone output."].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["set-test-point"].isEnabled)
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
        app.buttons["Done"].tap()
        XCTAssertFalse(app.buttons["set-test-point"].isEnabled)
        XCTAssertFalse(app.buttons["play-synthetic-route"].isEnabled)
    }
}
