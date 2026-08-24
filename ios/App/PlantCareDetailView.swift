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
    @State var weatherAlertsEnabled = true
    @State var showsDeleteConfirmation = false
    @State var saveError: String?
    @State var saveFeedback: String?
    @State var showsEditing = false

    var body: some View {
        VStack(spacing: 0) {
            detailTopBar
            ScrollView {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
                    hero
                    guideSection
                        .padding(.top, PlanteriorSpacing.extraSmall)
                    compactWateringCard
                        .padding(.top, PlanteriorSpacing.medium)
                    memoSection
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
                .padding(.horizontal, PlanteriorSpacing.large)
                .padding(.bottom, PlanteriorSpacing.large)
            }
            .plantCareReferenceBody()
            .scrollClipDisabled(!dynamicTypeSize.isAccessibilitySize)
            .accessibilityIdentifier("plant.detail.screen")
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .tint(PlanteriorPalette.accent.color)
        .task { loadPlant() }
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
        .frame(height: 220)
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
            Text(PlantCarePresentation.species(for: trimmedNickname))
                .font(PlanteriorTypography.supporting.italic())
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("plant.detail.species")
            Text(detailMetadata)
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("plant.detail.metadata")
        }
    }

    private var guideSection: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
            Text("식물 가이드 및 관리 기준")
                .font(PlanteriorTypography.sectionTitle)
            LazyVGrid(columns: guideColumns, spacing: PlanteriorSpacing.small) {
                ForEach(PlantCarePresentation.guideMetrics) { metric in
                    PlanteriorCard {
                        VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                            Label(metric.title, systemImage: metric.icon)
                                .font(PlanteriorTypography.caption.weight(.semibold))
                                .foregroundStyle(PlanteriorPalette.accent.color)
                            Text(metric.value)
                                .font(PlanteriorTypography.cardTitle)
                            Text(metric.hint)
                                .font(PlanteriorTypography.microLabel)
                                .foregroundStyle(PlanteriorPalette.textTertiary.color)
                        }
                        .padding(.vertical, -PlanteriorSpacing.extraSmall)
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("plant.detail.guide")
    }

    private var memoSection: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            Text("관리 메모")
                .font(PlanteriorTypography.sectionTitle)
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                Text(
                    privateMemo.isEmpty
                        ? "아직 작성한 관리 메모가 없어요."
                        : privateMemo
                )
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("plant.detail.memo.body")
                if let memoUpdatedOn {
                    Text("수정일: \(memoUpdatedOn)")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textTertiary.color)
                        .accessibilityIdentifier("plant.detail.memo-updated")
                }
            }
            .padding(.horizontal, PlanteriorSpacing.medium)
            .padding(.vertical, PlanteriorSpacing.large)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PlanteriorPalette.canvas.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("plant.detail.memo.card")
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                    .stroke(
                        PlanteriorPalette.border.color,
                        lineWidth: PlanteriorControl.hairline
                    )
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("plant.detail.memo")
    }

    private var memoUpdatedOn: String? {
        #if DEBUG
            guard let value = ProcessInfo.processInfo.environment[
                "QA_PLANT_DETAIL_UPDATED_ON"
            ] else {
                return nil
            }
            let components = value.split(separator: "-")
            guard components.count == 3 else { return nil }
            return "\(components[0]). \(components[1]). \(components[2])"
        #else
            return nil
        #endif
    }

    private var remedyLink: some View {
        NavigationLink {
            PlantSymptomRemedyView(
                displayName: trimmedNickname,
                hasWateringBaseline: lastWateredOn != nil
            )
        } label: {
            HStack(spacing: PlanteriorSpacing.medium) {
                PlanteriorIconWell(systemImage: "cross.case")
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text("증상 대처법")
                        .font(PlanteriorTypography.cardTitle)
                    Text("잎과 흙 상태를 직접 확인하는 방법")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                }
                Spacer(minLength: PlanteriorSpacing.small)
                Image(systemName: "chevron.right")
                    .foregroundStyle(PlanteriorPalette.textTertiary.color)
            }
            .padding(PlanteriorSpacing.large)
            .background(PlanteriorPalette.surface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                    .stroke(PlanteriorPalette.border.color, lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("plant.detail.remedy")
    }

    private var deleteAction: some View {
        Button("식물 삭제", role: .destructive) {
            showsDeleteConfirmation = true
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: PlanteriorControl.minimumTarget)
        .accessibilityIdentifier("plant.detail.delete")
    }

    private var guideColumns: [GridItem] {
        let count = dynamicTypeSize.isAccessibilitySize ? 1 : 2
        return Array(
            repeating: GridItem(.flexible(), spacing: 10),
            count: count
        )
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
