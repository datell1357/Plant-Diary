import Foundation
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct PlantRegistrationView: View {
    let method: RegistrationMethod
    let candidate: IdentificationCandidate?
    let onRegistered: (() -> Void)?
    @ObservedObject private var collection = LocalPlantCollectionStore.shared
    @State private var speciesSearch: String
    @State private var name: String
    @State private var lastWatered = Date()
    @State private var usesLastWateredDate = false
    @State private var saved = false
    private let plantCalendar = PlantCareCalendar()
    @State private var representativePhoto: Data?
    @State private var showsDuplicate = false
    @State private var existingRoute: DuplicatePlantRoute?
    @State private var editedIdentification = false
    @State private var selectedManualScientificName: String?

    init(
        method: RegistrationMethod = .manual,
        candidate: IdentificationCandidate? = nil,
        onRegistered: (() -> Void)? = nil
    ) {
        self.method = method
        self.candidate = candidate
        self.onRegistered = onRegistered
        let candidateName = candidate?.species.koreanName ?? ""
        _speciesSearch = State(initialValue: candidateName)
        _name = State(initialValue: candidateName)
    }

    var body: some View {
        Form {
            TextField("공개 식물 검색", text: $speciesSearch)
                .accessibilityIdentifier("registration.search")
                .onChange(of: speciesSearch) { _, _ in
                    selectedManualScientificName = nil
                }
            if method == .manual, !manualCareOptions.isEmpty {
                Section("공공데이터 식물 선택") {
                    ForEach(manualCareOptions, id: \.scientificName) { profile in
                        manualCareOption(profile)
                    }
                }
            }
            TextField("식물 이름", text: $name)
                .accessibilityIdentifier("registration.name")
                .onChange(of: name) {
                    editedIdentification = candidate != nil
                }
            Toggle("마지막 물 준 날짜 추가", isOn: $usesLastWateredDate)
            if usesLastWateredDate {
                DatePicker(
                    "마지막 물 준 날짜",
                    selection: $lastWatered,
                    in: ...Date(),
                    displayedComponents: .date
                )
                .datePickerStyle(.compact)
            }
            Button(
                representativePhoto == nil ? "대표 사진 선택" : "대표 사진 선택됨"
            ) {
                Task {
                    representativePhoto =
                        await IdentificationDraftStore.shared.load()?.data
                }
            }
            .accessibilityIdentifier("registration.photo.optional")
            PlanteriorPrimaryButton("식물 등록") {
                save()
            }
            .disabled(!isValidName)
            .accessibilityIdentifier("registration.submit")
            if saved {
                Text("등록이 완료되었어요.")
                    .accessibilityIdentifier("registration.saved")
                #if DEBUG
                    if let lastWateredOn = collection.plants.last?.lastWateredOn {
                        Text(lastWateredOn.rawValue)
                            .accessibilityIdentifier(
                                "registration.saved.last-watered"
                            )
                    }
                #endif
            }
        }
        .navigationTitle("식물 등록")
        .task {
            #if DEBUG
                let date = ProcessInfo.processInfo.environment[
                    "QA_REGISTRATION_LAST_WATERED_INSTANT"
                ].flatMap { ISO8601DateFormatter().date(from: $0) }
                if let date {
                    lastWatered = date
                    usesLastWateredDate = true
                }
            #endif
        }
        .confirmationDialog(
            "이미 등록한 식물이에요",
            isPresented: $showsDuplicate
        ) {
            Button("기존 식물 열기") { openExistingDuplicate() }
                .accessibilityIdentifier("registration.open-existing")
            Button("한 개 더 등록") { persist() }
            Button("취소", role: .cancel) {}
        }
        .navigationDestination(item: $existingRoute) { route in
            Group {
                if let index = collection.index(
                    forRouteTarget: route.target.rawValue
                ) {
                    PlantCareDetailView(index: index)
                } else {
                    Text("기존 식물을 찾을 수 없어요.")
                        .accessibilityIdentifier("route.unavailable")
                }
            }
        }
    }

    private var isValidName: Bool {
        let count = name.trimmingCharacters(in: .whitespacesAndNewlines).count
        return (1 ... 100).contains(count)
    }

    private func save() {
        let isDuplicate = candidate.map {
            collection.contains($0.plantID.rawValue)
        } ?? false
        if isDuplicate {
            showsDuplicate = true
            return
        }
        persist()
    }

    private func persist() {
        collection.save(
            PlantRegistrationDraft(
                plantID: candidate?.plantID,
                scientificName: candidate?.scientificName
                    ?? selectedManualScientificName,
                displayName: name.trimmingCharacters(
                    in: .whitespacesAndNewlines
                ),
                representativePhoto: representativePhoto,
                lastWateredOn: usesLastWateredDate ? calendarDate : nil,
                registrationMethod: method
                    == .identified && editedIdentification
                    ? .identificationEdited
                    : method
            )
        )
        saved = true
        Task { try? await IdentificationDraftStore.shared.clear() }
        onRegistered?()
    }

    private func openExistingDuplicate() {
        guard let rawValue = candidate?.plantID.rawValue,
              let target = PlantRouteTarget(rawValue: rawValue)
        else {
            return
        }
        existingRoute = DuplicatePlantRoute(target: target)
    }

    private var calendarDate: CalendarDate? {
        try? plantCalendar.calendarDate(from: lastWatered)
    }

    private var manualCareOptions: [DomesticPlantCareProfile] {
        DomesticPlantCareCatalog.manualOptions(matching: speciesSearch)
    }

    private func manualCareOption(
        _ profile: DomesticPlantCareProfile
    ) -> some View {
        Button {
            selectedManualScientificName = profile.scientificName
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text("몬스테라")
                    Text(profile.scientificName)
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                }
                Spacer()
                if selectedManualScientificName == profile.scientificName {
                    Label("선택됨", systemImage: "checkmark.circle.fill")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.accent.color)
                }
            }
        }
        .accessibilityIdentifier("registration.care-option.monstera-deliciosa")
        .accessibilityValue(
            selectedManualScientificName == profile.scientificName
                ? "선택됨"
                : "선택되지 않음"
        )
    }
}

private struct DuplicatePlantRoute: Identifiable, Hashable {
    let target: PlantRouteTarget

    var id: String {
        target.rawValue
    }
}

import PlanteriorData
