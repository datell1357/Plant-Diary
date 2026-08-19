extension LocalPlantCollectionStore {
    func containsRouteTarget(_ rawTarget: String) -> Bool {
        if rawTarget.hasPrefix("local-") {
            let indexText = rawTarget.dropFirst("local-".count)
            if let index = Int(indexText) {
                return plants.indices.contains(index)
            }
        }
        return plants.contains { $0.plantID?.rawValue == rawTarget }
    }
}
