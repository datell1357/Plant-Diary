import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

extension PlantCareDetailView {
    var editingSection: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
            Text("식물 정보 편집")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
                    detailField(
                        title: "별명",
                        placeholder: "식물 별명",
                        text: $nickname,
                        identifier: "plant.detail.nickname"
                    )
                    detailField(
                        title: "위치",
                        placeholder: "예: 거실 창가",
                        text: $location,
                        identifier: "plant.detail.location"
                    )
                    detailField(
                        title: "관리 메모",
                        placeholder: "나만 볼 수 있는 관리 메모",
                        text: $privateMemo,
                        identifier: "plant.detail.private-memo"
                    )
                    PlanteriorPrimaryButton("변경 저장", action: persistEdits)
                        .disabled(trimmedNickname.isEmpty)
                        .opacity(trimmedNickname.isEmpty ? 0.55 : 1)
                        .accessibilityIdentifier("plant.detail.save")
                }
            }
        }
    }

    var timelineSection: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
            Text("건강 기록")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
                    detailField(
                        title: "새 기록",
                        placeholder: "잎과 흙 상태를 남겨보세요",
                        text: $healthNote,
                        identifier: "plant.detail.note"
                    )
                    PlanteriorSecondaryButton("기록 추가", action: addHealthNote)
                        .disabled(trimmedHealthNote.isEmpty)
                        .opacity(trimmedHealthNote.isEmpty ? 0.55 : 1)
                        .accessibilityIdentifier("plant.detail.add-note")
                    if notes.isEmpty {
                        Text("아직 건강 기록이 없어요.")
                            .font(PlanteriorTypography.caption)
                            .foregroundStyle(PlanteriorPalette.textTertiary.color)
                    } else {
                        ForEach(Array(notes.enumerated()), id: \.offset) { item in
                            HStack(alignment: .top, spacing: PlanteriorSpacing.small) {
                                Circle()
                                    .fill(PlanteriorPalette.accent.color)
                                    .frame(width: 8, height: 8)
                                    .padding(.top, 6)
                                    .accessibilityHidden(true)
                                Text(item.element)
                                    .font(PlanteriorTypography.supporting)
                                    .accessibilityIdentifier("plant.detail.timeline")
                            }
                        }
                    }
                }
            }
        }
    }

    var trimmedNickname: String {
        nickname.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var trimmedHealthNote: String {
        healthNote.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func detailField(
        title: String,
        placeholder: String,
        text: Binding<String>,
        identifier: String
    ) -> some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
            Text(title)
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
            TextField(placeholder, text: text)
                .padding(.horizontal, PlanteriorSpacing.medium)
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .background(PlanteriorPalette.subtle.color)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
                .accessibilityIdentifier(identifier)
        }
    }

    private func addHealthNote() {
        guard !trimmedHealthNote.isEmpty else { return }
        notes.append(trimmedHealthNote)
        collection.addHealthNote(trimmedHealthNote, at: index)
        healthNote = ""
    }

    func persistEdits() {
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
