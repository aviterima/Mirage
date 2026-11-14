import XCTest
import MirageCore
@testable import Mirage

@MainActor
final class SavedStopPlannerTests: XCTestCase {
    private let home = SavedScenario(id: "home", name: "Home", kind: .place,
        dest: Coordinate(lat: 33.5, lng: -112.0), destName: "House pin",
        destAddress: "123 Cedar Street", favorite: true, aliases: ["My house"])
    private let office = SavedScenario(id: "office", name: "Office", kind: .place,
        dest: Coordinate(lat: 33.6, lng: -112.1), destAddress: "456 Oak Avenue")

    func testLocalNamesAliasesAddressesAndEmptyFilterDoNotStartOnlineSearch() {
        var requests = 0
        let planner = SavedStopPlanner(searchProvider: { _ in requests += 1; return [] })
        let library = [office, home, SavedScenario(id: "route", name: "Home route", kind: .route, dest: home.dest)]
        XCTAssertEqual(planner.savedMatches(library).map(\.id), ["home", "office"])
        for query in ["H", "my hou", "CEDAR"] {
            planner.query = query
            XCTAssertEqual(planner.savedMatches(library).map(\.id), ["home"])
        }
        planner.query = "not present"
        XCTAssertTrue(planner.savedMatches(library).isEmpty)
        planner.query = ""
        XCTAssertEqual(planner.savedMatches(library).count, 2)
        XCTAssertEqual(requests, 0)
        XCTAssertTrue(planner.stops.isEmpty)
    }

    func testRepeatedPlacesRemainSeparateAndCanBeMovedOrRemoved() {
        let planner = SavedStopPlanner()
        planner.add(home); planner.add(office); planner.add(home)
        XCTAssertEqual(planner.stops.map(\.name), ["Home", "Office", "Home"])
        XCTAssertEqual(Set(planner.stops.map(\.id)).count, 3)
        let last = planner.stops[2].id
        planner.move(last, by: -1)
        XCTAssertEqual(planner.stops.map(\.name), ["Home", "Home", "Office"])
        planner.remove(last)
        XCTAssertEqual(planner.stops.map(\.point), [home.dest!, office.dest!])
        XCTAssertEqual(planner.sequenceName, "Home → Office")
        planner.add(SavedScenario(id: "invalid", name: "Invalid", kind: .place, dest: Coordinate(lat: 999, lng: 0)))
        XCTAssertEqual(planner.stops.count, 2)
        planner.clear()
        XCTAssertTrue(planner.stops.isEmpty)
    }

    func testSavedSelectionCancelsLateOnlineResultsWithoutChangingStops() async {
        let started = expectation(description: "Online request started")
        let returned = expectation(description: "Uncancellable request returned")
        var completion: CheckedContinuation<[StopChoice], Never>?
        let planner = SavedStopPlanner(searchProvider: { _ in
            started.fulfill()
            let result = await withCheckedContinuation { completion = $0 }
            returned.fulfill()
            return result
        })
        planner.query = "Home"; planner.searchOtherPlaces()
        await fulfillment(of: [started], timeout: 3)
        planner.add(home)
        completion?.resume(returning: [StopChoice(id: "late", name: "Late result", address: "", point: office.dest!)])
        await fulfillment(of: [returned], timeout: 3)
        for _ in 0..<5 { await Task.yield() }
        XCTAssertEqual(planner.stops.map(\.point), [home.dest!])
        XCTAssertTrue(planner.remoteMatches.isEmpty)
        XCTAssertEqual(planner.query, "")
        XCTAssertFalse(planner.searching)
    }

    func testOnlineFailureKeepsSavedChoicesAndRequiresExplicitSelection() async {
        let returned = expectation(description: "Online failure")
        let planner = SavedStopPlanner(searchProvider: { _ in
            returned.fulfill()
            throw NSError(domain: "Fixture", code: 1)
        })
        planner.query = "Home"
        planner.searchOtherPlaces()
        await fulfillment(of: [returned], timeout: 3)
        for _ in 0..<5 { await Task.yield() }
        XCTAssertEqual(planner.savedMatches([home, office]).map(\.id), ["home"])
        XCTAssertTrue(planner.stops.isEmpty)
        XCTAssertNotNil(planner.message)
        XCTAssertFalse(planner.searching)
    }
}
