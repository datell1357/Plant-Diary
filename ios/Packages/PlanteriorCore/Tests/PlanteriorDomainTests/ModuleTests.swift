@testable import PlanteriorDomain
import Testing

struct ModuleTests {
    @Test
    func domainModuleHasStableName() {
        #expect(PlanteriorDomainModule.name == "PlanteriorDomain")
    }
}
