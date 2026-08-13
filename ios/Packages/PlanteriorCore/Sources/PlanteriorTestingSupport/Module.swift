import PlanteriorDomain

public enum PlanteriorTestingSupportModule {
    public static func fixedAccountID() throws -> AccountID {
        try AccountID.parse("fixture-account")
    }
}
