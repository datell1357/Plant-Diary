import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct PlantCareDetailView: View {
    let index: Int
    let plantCalendar = PlantCareCalendar()
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var collection = LocalPlantCollectionStore.shared
    @State private var nickname = ""
    @State private var healthNote = ""
    @State private var notes: [String] = []
    @State private var location = ""
    @State private var privateMemo = ""
    @State var lastWateredOn: Date?
    @State var wateringIntervalDays = 10
    @State var wateringFeedback: WateringFeedback?
    @State private var showsDeleteConfirmation = false
    @State private var saveError: String?

    var body: some View {
        Form {
            Section("식물 정보") {
                TextField("별명", text: $nickname)
                    .accessibilityIdentifier("plant.detail.nickname")
                TextField("위치", text: $location)
                    .accessibilityIdentifier("plant.detail.location")
                TextField("비공개 메모", text: $privateMemo)
                    .accessibilityIdentifier("plant.detail.private-memo")
                Button("변경 저장") { persistEdits() }
                    .disabled(trimmedNickname.isEmpty)
                    .accessibilityIdentifier("plant.detail.save")
            }
            Section("물 주기 일정") {
                if let lastWateredOn {
                    DatePicker(
                        "마지막 물 주기",
                        selection: Binding(
                            get: { lastWateredOn },
                            set: { self.lastWateredOn = $0 }
                        ),
                        in: ...todayDate,
                        displayedComponents: .date
                    )
                    .accessibilityIdentifier("watering.last-date-picker")
                    Text("마지막 물 주기: \(calendarDate?.rawValue ?? "-")")
                        .accessibilityIdentifier("watering.last-date")
                } else {
                    Text("마지막 물 주기일을 설정하면 다음 일정을 계산해요.")
                        .accessibilityIdentifier("watering.missing-date")
                    Button("마지막 물 주기 오늘로 설정") {
                        setWateringBaselineToday()
                    }
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .accessibilityIdentifier("watering.set-today")
                }
                Stepper(
                    "물 주기 간격: \(wateringIntervalDays)일",
                    value: $wateringIntervalDays,
                    in: 1 ... 30
                )
                .accessibilityIdentifier("watering.interval")
                wateringScheduleContent
                Button(wateringButtonTitle) {
                    recordWateredToday()
                }
                .disabled(lastWateredOn == nil)
                .foregroundStyle(wateringButtonColor)
                .accessibilityIdentifier("watering.complete")
            }
            Section("건강 기록") {
                TextField("건강 메모", text: $healthNote)
                    .accessibilityIdentifier("plant.detail.note")
                Button("기록 추가") {
                    notes.append(healthNote.trimmingCharacters(
                        in: .whitespacesAndNewlines
                    ))
                    collection.addHealthNote(
                        healthNote.trimmingCharacters(
                            in: .whitespacesAndNewlines
                        ),
                        at: index
                    )
                    healthNote = ""
                }
                .disabled(
                    healthNote.trimmingCharacters(
                        in: .whitespacesAndNewlines
                    ).isEmpty
                )
                .accessibilityIdentifier("plant.detail.add-note")
                ForEach(notes, id: \.self) {
                    Text($0)
                        .accessibilityIdentifier("plant.detail.timeline")
                }
            }
            Section("관리 가이드") {
                Text("물: 흙이 마르면 충분히 주세요.")
                Text("빛: 밝은 간접광을 권장해요.")
                Text("온도: 18–27°C")
                Text("습도: 40–70%")
                DisclosureGroup("증상 · 원인 · 행동") {
                    Text("잎 처짐 · 수분 부족 가능성 · 흙 상태를 확인하세요.")
                }
            }
            if let saveError {
                Text(saveError)
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("plant.detail.save-error")
            }
            Section {
                Button("식물 삭제", role: .destructive) {
                    showsDeleteConfirmation = true
                }
                .accessibilityIdentifier("plant.detail.delete")
            }
        }
        .scrollContentBackground(.hidden)
        .background(PlanteriorPalette.canvas.color)
        .tint(PlanteriorPalette.accent.color)
        .navigationTitle(trimmedNickname)
        .task {
            guard collection.plants.indices.contains(index) else {
                return
            }
            nickname = collection.plants[index].displayName
            location = collection.plants[index].location ?? ""
            privateMemo = collection.plants[index].privateMemo ?? ""
            notes = collection.healthNotes[index] ?? []
            wateringIntervalDays = collection.wateringIntervalDays(at: index)
            lastWateredOn = collection.plants[index].lastWateredOn.flatMap(date)
            #if DEBUG
                let draftDate = ProcessInfo.processInfo.environment[
                    "QA_WATERING_DRAFT_DATE"
                ].flatMap { try? CalendarDate.parse($0) }
                if let draftDate {
                    lastWateredOn = date(draftDate)
                }
            #endif
        }
        .confirmationDialog(
            "이 식물을 삭제할까요?",
            isPresented: $showsDeleteConfirmation
        ) {
            Button("삭제", role: .destructive) {
                collection.remove(at: index)
                dismiss()
            }
            .accessibilityIdentifier("plant.detail.delete-confirm")
            Button("취소") {}
                .accessibilityIdentifier("plant.detail.delete-cancel")
        }
    }

    private var trimmedNickname: String {
        nickname.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func persistEdits() {
        guard let todayCalendarDate else {
            saveError = "현재 날짜를 확인하지 못했어요."
            return
        }
        do {
            try collection.update(
                at: index,
                edits: PlantCareEdits(
                    displayName: trimmedNickname,
                    location: location.isEmpty ? nil : location,
                    note: privateMemo.isEmpty ? nil : privateMemo,
                    lastWateredOn: calendarDate,
                    wateringIntervalDays: wateringIntervalDays
                ),
                today: todayCalendarDate
            )
            saveError = nil
        } catch PlantCareValidationError.invalidLocation {
            saveError = "위치는 50자 이하로 입력해 주세요."
        } catch PlantCareValidationError.invalidMemo {
            saveError = "비공개 메모는 1000자 이하로 입력해 주세요."
        } catch WateringScheduleError.futureLastWateredDate {
            saveError = "마지막 물 주기일은 오늘 이후로 설정할 수 없어요."
        } catch WateringScheduleError.invalidInterval {
            saveError = "물 주기 간격은 하루 이상이어야 해요."
        } catch {
            saveError = "변경사항을 저장하지 못했어요."
        }
    }
}
