import Foundation
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct PlantRegistrationView: View {
    let method: RegistrationMethod
    let candidate: IdentificationCandidate?
    @ObservedObject private var collection = LocalPlantCollectionStore.shared
    @State private var name = ""
    @State private var lastWatered = Date()
    @State private var usesLastWateredDate = false
    @State private var saved = false
    private let plantCalendar = PlantCareCalendar()
    @State private var representativePhoto: Data?
    @State private var showsDuplicate = false
    @State private var openedExisting = false
    @State private var editedIdentification = false

    init(
        method: RegistrationMethod = .manual,
        candidate: IdentificationCandidate? = nil
    ) {
        self.method = method
        self.candidate = candidate
    }

    var body: some View {
        Form {
            TextField("공개 식물 검색", text: $name)
                .accessibilityIdentifier("registration.search")
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
            Button("기존 식물 열기") { openedExisting = true }
                .accessibilityIdentifier("registration.open-existing")
            Button("한 개 더 등록") { persist() }
            Button("취소", role: .cancel) {}
        }
        .navigationDestination(isPresented: $openedExisting) {
            Text("기존 식물 상세")
                .accessibilityIdentifier("registration.existing-detail")
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
    }

    private var calendarDate: CalendarDate? {
        try? plantCalendar.calendarDate(from: lastWatered)
    }
}

import PlanteriorData
