import FirebaseFirestore
import Foundation

enum FirestorePayloadJSON {
    static func normalize(_ value: Any) -> Any {
        switch value {
        case let timestamp as Timestamp:
            timestamp.dateValue().timeIntervalSince1970
        case let date as Date:
            date.timeIntervalSince1970
        case let reference as DocumentReference:
            reference.path
        case let point as GeoPoint:
            [
                "latitude": point.latitude,
                "longitude": point.longitude
            ]
        case let values as [Any]:
            values.map(normalize)
        case let values as [String: Any]:
            values.mapValues(normalize)
        case is NSNull, is String, is NSNumber:
            value
        default:
            String(describing: value)
        }
    }
}
