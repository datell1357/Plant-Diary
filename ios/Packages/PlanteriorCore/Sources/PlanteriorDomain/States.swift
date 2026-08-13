public enum DataFreshness: Equatable, Sendable {
    case fresh
    case stale(lastUpdated: Instant)
}

public enum ScreenState<Value: Equatable & Sendable>: Equatable, Sendable {
    case idle
    case loading(previous: Value?)
    case ready(value: Value, freshness: DataFreshness)
    case empty
    case failed(error: DomainFailure, cached: Value?)
}

public enum MutationState<Draft: Equatable & Sendable>: Equatable, Sendable {
    case editing(Draft)
    case submitting(Draft)
    case queued(Draft)
    case succeeded
    case conflicted(Draft, actualRevision: Revision)
    case failed(Draft, DomainFailure)
}

public enum DomainFailure: Error, Equatable, Sendable {
    case validation
    case forbidden
    case notFound
    case conflict(actualRevision: Revision)
    case transient
    case configuration
}

public enum MutationTransitionError: Error, Equatable, Sendable {
    case invalidTransition
}

public extension MutationState {
    func submit() throws -> MutationState {
        guard case let .editing(draft) = self else {
            throw MutationTransitionError.invalidTransition
        }
        return .submitting(draft)
    }

    func queue() throws -> MutationState {
        guard case let .submitting(draft) = self else {
            throw MutationTransitionError.invalidTransition
        }
        return .queued(draft)
    }

    func fail(_ failure: DomainFailure) throws -> MutationState {
        switch self {
        case let .editing(draft),
             let .submitting(draft),
             let .queued(draft):
            return .failed(draft, failure)
        case let .conflicted(draft, _):
            return .failed(draft, failure)
        case .succeeded,
             .failed:
            throw MutationTransitionError.invalidTransition
        }
    }
}
