import CoreLocation
import Combine
import MirageCore

@MainActor
final class LocationObserver: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var status = "Location permission not requested"
    @Published var latest: CLLocation?
    @Published var lastReal: CLLocation?
    @Published var sampleInterval: TimeInterval?
    var onObservation: ((LocationObservation) -> Void)?
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    func request() {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            status = "Waiting for a fresh location"
            manager.startUpdatingLocation()
        case .denied, .restricted: status = "Allow location access in iPhone Settings to verify output"
        @unknown default: status = "Location permission unavailable"
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            request()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last, fix.horizontalAccuracy >= 0,
              abs(fix.timestamp.timeIntervalSinceNow) <= 10 else { return }
        if let latest, fix.timestamp > latest.timestamp { sampleInterval = fix.timestamp.timeIntervalSince(latest.timestamp) }
        latest = fix
        let simulated = fix.sourceInformation?.isSimulatedBySoftware
        if simulated == false { lastReal = fix }
        status = simulated == true ? "Observed software-simulated location" :
            simulated == false ? "Observed real location" : "Observed location; source unknown"
        onObservation?(LocationObservation(coordinate: Coordinate(lat: fix.coordinate.latitude,
            lng: fix.coordinate.longitude), timestamp: fix.timestamp, isSimulatedBySoftware: simulated,
            horizontalAccuracy: fix.horizontalAccuracy))
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        status = "Location observation unavailable. Check permission and GPS reception."
    }
}
