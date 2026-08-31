import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct PlantCareDetailView: View {
    let index: Int
    let plantCalendar = PlantCareCalendar()
    @Environment(\.dismiss) var dismiss
    @Environment(\.dynamicTypeSize) var dynamicTypeSize
    @ObservedObject var collection = LocalPlantCollectionStore.shared
    @State var nickname = ""
    @State var healthNote = ""
    @State var notes: [String] = []
    @State var location = ""
    @State var privateMemo = ""
    @State var lastWateredOn: Date?
    @State var wateringIntervalDays = 10
    @State var wateringFeedback: WateringFeedback?
    @State var wateringUndoBaseline: (
        lastWateredOn: CalendarDate?,
        intervalDays: Int
    )?
    @State var notificationState = NotificationRuntimeState.initial
    @State var weatherAlertsEnabled = true
    @State var showsDeleteConfirmation = false
    @State var saveError: String?
    @State var saveFeedback: String?
    @State var showsEditing = false

    var body: some View {
        VStack(spacing: 0) {
            detailTopBar
                .zIndex(1)
            GeometryReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
                        hero
                        guideSection
                            .padding(.top, PlanteriorSpacing.extraSmall)
                        compactWateringCard
                            .padding(
                                .top,
                                PlantCareReferenceMetrics.guideToWateringInset
                            )
                        memoSection
                            .padding(.top, PlantCareReferenceMetrics.memoTopInset)
                        remedyLink
                        weatherSection
                        if showsEditing {
                            titleSummary
                            wateringEditorSection
                            editingSection
                        }
                        timelineSection
                        saveFeedbackLabel
                        if let saveError {
                            Text(saveError)
                                .font(PlanteriorTypography.supporting)
                                .foregroundStyle(PlanteriorPalette.warning.color)
                                .accessibilityIdentifier("plant.detail.save-error")
                        }
                        deleteAction
                    }
                    .frame(
                        width: proxy.size.width - (PlanteriorSpacing.large * 2),
                        alignment: .leading
                    )
                    .padding(.horizontal, PlanteriorSpacing.large)
                    .padding(
                        .bottom,
                        PlantCareReferenceMetrics.detailBottomScrollClearance
                    )
                }
                .plantCareReferenceBody()
                .scrollClipDisabled(!dynamicTypeSize.isAccessibilitySize)
                .accessibilityIdentifier("plant.detail.screen")
            }
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .tint(PlanteriorPalette.accent.color)
        .task {
            loadPlant()
            notificationState = await NotificationRuntimeState.current()
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

    private var hero: some View {
        GeometryReader { proxy in
            Image(.collectionHero)
                .resizable()
                .scaledToFill()
                .frame(width: proxy.size.width, height: proxy.size.height)
                .clipped()
        }
        .frame(height: PlantCareReferenceMetrics.heroHeight)
        .background(PlanteriorPalette.subtle.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
        .offset(y: PlantCareReferenceMetrics.heroTopInset)
        .accessibilityLabel("\(trimmedNickname) 대표 이미지")
        .accessibilityIdentifier("plant.detail.hero")
    }

    private var titleSummary: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
            Text(trimmedNickname)
                .font(PlanteriorTypography.pageTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("plant.detail.title")
            let species = plant.map(PlantCarePresentation.species(for:))
                ?? PlantCarePresentation.species(for: trimmedNickname)
            Text(KoreanTypography.atomic(species))
                .font(PlanteriorTypography.supporting.italic())
                .accessibilityLabel(species)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("plant.detail.species")
            Text(detailMetadata)
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("plant.detail.metadata")
        }
    }

    private var deleteAction: some View {
        Button("식물 삭제", role: .destructive) {
            showsDeleteConfirmation = true
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: PlanteriorControl.minimumTarget)
        .accessibilityIdentifier("plant.detail.delete")
    }

    private var detailMetadata: String {
        guard collection.plants.indices.contains(index) else { return "" }
        let plant = collection.plants[index]
        let place = plant.location.flatMap { $0.isEmpty ? nil : $0 }
            ?? "위치 미설정"
        let method = plant.registrationMethod == .manual ? "직접 등록" : "사진 식별"
        return "\(place) · \(method)"
    }
}
