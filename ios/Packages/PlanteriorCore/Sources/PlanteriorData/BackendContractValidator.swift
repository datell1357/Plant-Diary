public enum BackendContractValidator {
    public static func validateOwnerPath(_ value: String, ownerUID: String) throws {
        guard value.hasPrefix("users/\(ownerUID)/") else {
            throw BackendContractError.invalidFixture
        }
    }

    public static func validateClientWritablePath(_ value: String) throws {
        let serverOnly = [
            "notificationDeliveries", "weatherSnapshots", "weatherRisks",
            "deletionRequests", "ownedItems", "shareLinks"
        ]
        guard !serverOnly.contains(where: value.contains) else {
            throw BackendContractError.invalidFixture
        }
    }

    public static func validateStorageFilename(_ value: String) throws {
        guard value.count <= 160,
              value.first?.isLetter == true || value.first?.isNumber == true,
              !value.contains("..")
        else {
            throw BackendContractError.invalidFixture
        }
    }

    public static func validatePlacementTargets(_ value: String) throws {
        let targets = value.split(separator: "+")
        guard targets.count == 1,
              targets[0] == "plantId" || targets[0] == "itemId"
        else {
            throw BackendContractError.invalidFixture
        }
    }

    public static func validateRegistrationMethod(_ value: String) throws {
        let method = value.replacingOccurrences(of: "registrationMethod=", with: "")
        guard ["IDENTIFIED", "IDENTIFICATION_EDITED", "MANUAL"].contains(method) else {
            throw BackendContractError.invalidFixture
        }
    }
}
