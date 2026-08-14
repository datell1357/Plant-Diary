import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct PlantCareDetailView: View {
    let index: Int
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var collection = LocalPlantCollectionStore.shared
    @State private var nickname = ""
    @State private var healthNote = ""
    @State private var notes: [String] = []
    @State private var location = ""
    @State private var privateMemo = ""
    @State private var lastWateredOn = Date()
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
                DatePicker(
                    "마지막 물 주기",
                    selection: $lastWateredOn,
                    displayedComponents: .date
                )
                Button("변경 저장") { persistEdits() }
                    .disabled(trimmedNickname.isEmpty)
                    .accessibilityIdentifier("plant.detail.save")
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
        .navigationTitle(trimmedNickname)
        .task {
            guard collection.plants.indices.contains(index) else {
                return
            }
            nickname = collection.plants[index].displayName
            location = collection.plants[index].location ?? ""
            privateMemo = collection.plants[index].privateMemo ?? ""
            notes = collection.healthNotes[index] ?? []
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
        do {
            try collection.update(
                at: index,
                displayName: trimmedNickname,
                location: location.isEmpty ? nil : location,
                note: privateMemo.isEmpty ? nil : privateMemo,
                lastWateredOn: calendarDate
            )
            saveError = nil
        } catch PlantCareValidationError.invalidLocation {
            saveError = "위치는 50자 이하로 입력해 주세요."
        } catch PlantCareValidationError.invalidMemo {
            saveError = "비공개 메모는 1000자 이하로 입력해 주세요."
        } catch {
            saveError = "변경사항을 저장하지 못했어요."
        }
    }

    private var calendarDate: CalendarDate? {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd"
        return try? CalendarDate.parse(formatter.string(from: lastWateredOn))
    }
}
