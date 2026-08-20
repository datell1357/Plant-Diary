extension LocalPlantCollectionStore {
    func index(forRouteTarget rawTarget: String) -> Int? {
        plants.firstIndex { $0.plantID?.rawValue == rawTarget }
    }

    func containsRouteTarget(_ rawTarget: String) -> Bool {
        index(forRouteTarget: rawTarget) != nil
    }
}
