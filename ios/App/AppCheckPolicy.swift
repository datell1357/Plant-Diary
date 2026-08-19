enum AppCheckPolicy {
    static func accepts(token: String?) -> Bool {
        guard let token else { return false }
        return token.count >= 32
    }
}
