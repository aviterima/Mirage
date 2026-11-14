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
    var onRecordingUnavailable: (() -> Void)?
    private var recordingReal = false
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
        case .denied, .restricted:
            status = "Allow location access in iPhone Settings to record or verify locations"
            if recordingReal { endRealRecording(); onRecordingUnavailable?() }
        @unknown default: status = "Location permission unavailable"
        }
    }

    func startRealRecording() {
        recordingReal = true
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.pausesLocationUpdatesAutomatically = false
        request()
    }

    func endRealRecording() {
        guard recordingReal else { return }
        recordingReal = false
        manager.allowsBackgroundLocationUpdates = false
        manager.pausesLocationUpdatesAutomatically = true
        manager.stopUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            request()
        } else if recordingReal && (manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted) {
            status = "Location permission was denied or removed; recording stopped"
            endRealRecording(); onRecordingUnavailable?()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for fix in locations.sorted(by: { $0.timestamp < $1.timestamp }) {
            guard fix.horizontalAccuracy >= 0, abs(fix.timestamp.timeIntervalSinceNow) <= 10 else { continue }
            let simulated = fix.sourceInformation?.isSimulatedBySoftware
            if latest == nil || fix.timestamp >= latest!.timestamp {
                if let latest, fix.timestamp > latest.timestamp { sampleInterval = fix.timestamp.timeIntervalSince(latest.timestamp) }
                latest = fix
                if simulated == false { lastReal = fix }
                status = simulated == true ? "Observed software-simulated location" :
                    simulated == false ? "Observed real location" : "Observed location; source unknown"
            }
            onObservation?(LocationObservation(coordinate: Coordinate(lat: fix.coordinate.latitude,
                lng: fix.coordinate.longitude), timestamp: fix.timestamp, isSimulatedBySoftware: simulated,
                horizontalAccuracy: fix.horizontalAccuracy))
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        status = "Location observation unavailable. Check permission and GPS reception."
        if (error as? CLError)?.code == .denied && recordingReal {
            endRealRecording(); onRecordingUnavailable?()
        }
    }
}
