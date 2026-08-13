import PlanteriorDomain

public enum PlanteriorDataModule {
    public static func legacyAccountID() throws -> AccountID {
        try AccountID.parse("legacy")
    }
}
