import PlanteriorDomain

public struct HomeCareCandidate: Equatable, Sendable {
    public let plantID: PersonalPlantID
    public let displayName: String
    public let lastWateredDate: CalendarDate?
    public let intervalDays: Int

    public init(
        plantID: PersonalPlantID,
        displayName: String,
        lastWateredDate: CalendarDate?,
        intervalDays: Int
    ) {
        self.plantID = plantID
        self.displayName = displayName
        self.lastWateredDate = lastWateredDate
        self.intervalDays = intervalDays
    }
}

public enum HomeCareStatus: Equatable, Sendable {
    case overdue(nextDate: CalendarDate)
    case due(nextDate: CalendarDate)
    case upcoming(nextDate: CalendarDate)
    case unavailable
}

public struct HomeCareItem: Equatable, Sendable {
    public let plantID: PersonalPlantID
    public let displayName: String
    public let status: HomeCareStatus
}

public enum HomeWeatherState: Equatable, Sendable {
    case content(summary: String)
    case loading
    case failed
    case unavailable
}

public enum HomeDashboardState: Equatable, Sendable {
    case empty
    case content
    case partial
    case stale
}

public struct HomeDashboardSnapshot: Equatable, Sendable {
    public let careItems: [HomeCareItem]
    public let weather: HomeWeatherState
    public let state: HomeDashboardState

    public init(
        careItems: [HomeCareItem],
        weather: HomeWeatherState,
        state: HomeDashboardState = .empty
    ) {
        self.careItems = careItems
        self.weather = weather
        self.state = state
    }
}

public struct HomeDashboardCoordinator: Sendable {
    private let today: CalendarDate

    public init(today: CalendarDate) {
        self.today = today
    }

    public func snapshot(
        candidates: [HomeCareCandidate],
        weather: HomeWeatherState,
        freshness: DataFreshness = .fresh
    ) throws -> HomeDashboardSnapshot {
        let careItems = candidates
            .map(careItem)
            .sorted(by: careOrder)
        return HomeDashboardSnapshot(
            careItems: careItems,
            weather: weather,
            state: dashboardState(
                careItems: careItems,
                weather: weather,
                freshness: freshness
            )
        )
    }

    private func careItem(
        _ candidate: HomeCareCandidate
    ) -> HomeCareItem {
        guard let lastWateredDate = candidate.lastWateredDate else {
            return HomeCareItem(
                plantID: candidate.plantID,
                displayName: candidate.displayName,
                status: .unavailable
            )
        }
        var watering = WateringScheduleCoordinator(today: today)
        do {
            try watering.setSchedule(
                plantID: candidate.plantID,
                lastWateredDate: lastWateredDate,
                intervalDays: candidate.intervalDays
            )
        } catch {
            return HomeCareItem(
                plantID: candidate.plantID,
                displayName: candidate.displayName,
                status: .unavailable
            )
        }
        let status: HomeCareStatus = switch watering.status(
            for: candidate.plantID
        ) {
        case let .overdue(nextDate):
            .overdue(nextDate: nextDate)
        case let .due(nextDate):
            .due(nextDate: nextDate)
        case let .upcoming(nextDate):
            .upcoming(nextDate: nextDate)
        case .unavailable:
            .unavailable
        }
        return HomeCareItem(
            plantID: candidate.plantID,
            displayName: candidate.displayName,
            status: status
        )
    }

    private func careOrder(
        _ lhs: HomeCareItem,
        _ rhs: HomeCareItem
    ) -> Bool {
        let lhsOrder = orderKey(lhs.status)
        let rhsOrder = orderKey(rhs.status)
        if lhsOrder != rhsOrder {
            return lhsOrder < rhsOrder
        }
        if lhs.displayName != rhs.displayName {
            return lhs.displayName < rhs.displayName
        }
        return lhs.plantID.rawValue < rhs.plantID.rawValue
    }

    private func orderKey(_ status: HomeCareStatus) -> Int {
        switch status {
        case .overdue: 0
        case .due: 1
        case .upcoming: 2
        case .unavailable: 3
        }
    }

    private func dashboardState(
        careItems: [HomeCareItem],
        weather: HomeWeatherState,
        freshness: DataFreshness
    ) -> HomeDashboardState {
        guard !careItems.isEmpty else {
            return .empty
        }
        if case .stale = freshness {
            return .stale
        }
        if weather == .failed {
            return .partial
        }
        return .content
    }
}
