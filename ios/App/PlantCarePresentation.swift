import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension PlantCollectionView {
    var plantRows: some View {
        VStack(spacing: PlanteriorSpacing.small) {
            ForEach(filteredPlants, id: \.offset) { item in
                plantRow(item)
            }
        }
    }

    func plantRow(
        _ item: (offset: Int, element: PlantRegistrationDraft)
    ) -> some View {
        let identity = collection.presentationIdentity(at: item.offset)
            ?? "draft-\(item.element.displayName)"
        let status = careStatus(for: item)
        return NavigationLink {
            PlantCareDetailView(index: item.offset)
        } label: {
            HStack(spacing: PlanteriorSpacing.medium) {
                Image(PlantCarePresentation.asset(for: identity))
                    .resizable()
                    .scaledToFill()
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
                    .accessibilityLabel("\(item.element.displayName) 이미지")
                    .accessibilityIdentifier("collection.image.\(identity)")
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    HStack(spacing: PlanteriorSpacing.small) {
                        Text(item.element.displayName)
                            .font(PlanteriorTypography.cardTitle)
                        Circle()
                            .fill(PlanteriorPalette.accent.color)
                            .frame(width: 6, height: 6)
                            .accessibilityHidden(true)
                    }
                    PlanteriorStatusPill(
                        LocalizedStringKey(status.title),
                        variant: status.variant
                    )
                    .accessibilityIdentifier("collection.status.\(item.offset)")
                    Text(careMetadata(for: item.element))
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                }
                Spacer(minLength: PlanteriorSpacing.small)
                Image(systemName: "chevron.right")
                    .foregroundStyle(PlanteriorPalette.textTertiary.color)
                    .accessibilityHidden(true)
            }
            .padding(PlanteriorSpacing.medium)
            .background(PlanteriorPalette.surface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("collection.row.\(item.offset)")
        .accessibilityLabel(item.element.displayName)
        .accessibilityValue(status.title)
        .onAppear { collection.rememberScrollAnchor(item.offset) }
    }

    var trueEmptyState: some View {
        VStack(
            spacing: sizeCategory.isAccessibilityCategory
                ? PlanteriorSpacing.extraSmall
                : PlanteriorSpacing.medium
        ) {
            Image(.collectionEmptyAvatar)
                .resizable()
                .scaledToFit()
                .frame(
                    width: sizeCategory.isAccessibilityCategory ? 100 : 120,
                    height: sizeCategory.isAccessibilityCategory ? 100 : 120
                )
                .padding(sizeCategory.isAccessibilityCategory ? 0 : 20)
                .background(PlanteriorPalette.subtle.color)
                .clipShape(Circle())
                .accessibilityLabel("빈 화분 캐릭터")
                .accessibilityIdentifier("collection.empty.illustration")
            Text("아직 등록된 식물이 없어요 😢")
                .font(PlanteriorTypography.sectionTitle)
                .accessibilityIdentifier("collection.empty.title")
            Text(
                sizeCategory.isAccessibilityCategory
                    ? "첫 반려식물을 등록해 보세요"
                    : "첫 번째 반려식물을 등록하고 성장기를 남겨보세요"
            )
            .font(PlanteriorTypography.supporting)
            .accessibilityLabel("첫 번째 반려식물을 등록하고 성장기를 남겨보세요")
            .foregroundStyle(PlanteriorPalette.textSecondary.color)
            .multilineTextAlignment(.center)
            .accessibilityIdentifier("collection.empty.body")
            PlanteriorPrimaryButton("사진으로 식별하기", action: openCamera)
                .accessibilityIdentifier("collection.empty.camera")
            NavigationLink {
                PlantRegistrationView()
            } label: {
                Text("직접 등록하기")
                    .font(PlanteriorTypography.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: PlanteriorControl.primaryButtonHeight)
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .background(PlanteriorPalette.surface.color)
                    .clipShape(Capsule())
                    .overlay {
                        Capsule().stroke(PlanteriorPalette.accent.color, lineWidth: 1)
                    }
            }
            .accessibilityIdentifier("collection.empty.manual")
        }
        .padding(.vertical, PlanteriorSpacing.large)
    }

    func careStatus(
        for item: (offset: Int, element: PlantRegistrationDraft)
    ) -> (title: String, variant: PlanteriorStatusVariant) {
        guard let today else { return ("일정 확인 필요", .neutral) }
        switch collection.wateringStatus(
            at: item.offset,
            lastWateredOn: item.element.lastWateredOn,
            today: today,
            intervalDays: collection.wateringIntervalDays(at: item.offset)
        ) {
        case .unavailable:
            return ("물 주기 미설정", .neutral)
        case .overdue:
            return ("물주기 지연", .warning)
        case .due:
            return ("오늘 물주기", .warning)
        case let .upcoming(nextDate):
            return ("D-\(max(daysBetween(today, nextDate), 0))", .neutral)
        }
    }

    func careMetadata(for plant: PlantRegistrationDraft) -> String {
        guard let date = plant.lastWateredOn else {
            return "마지막 물 주기 기록 없음"
        }
        return "마지막 물 주기 · \(date.rawValue)"
    }

    @ViewBuilder
    var stateBanner: some View {
        switch collection.snapshotState {
        case .loading:
            ProgressView("도감을 불러오는 중")
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("collection.loading")
        case .error:
            statusMessage("도감을 불러오지 못했어요", icon: "exclamationmark.triangle")
                .accessibilityIdentifier("collection.error")
        case .partial:
            statusMessage("일부 식물 정보만 표시 중이에요.", icon: "leaf")
                .accessibilityIdentifier("collection.partial")
        case .stale:
            statusMessage("저장된 정보를 표시하고 있어요.", icon: "clock.arrow.circlepath")
                .accessibilityIdentifier("collection.stale")
        case .content:
            EmptyView()
        }
    }

    private func statusMessage(_ text: String, icon: String) -> some View {
        Label(text, systemImage: icon)
            .font(PlanteriorTypography.caption)
            .foregroundStyle(PlanteriorPalette.textSecondary.color)
            .padding(PlanteriorSpacing.medium)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PlanteriorPalette.subtle.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
    }

    var searchEmptyState: some View {
        VStack(spacing: PlanteriorSpacing.small) {
            Image(systemName: "leaf")
                .font(.system(size: 40))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text("검색 결과가 없어요")
                .font(PlanteriorTypography.sectionTitle)
            Text("다른 검색어를 입력해 주세요.")
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
        }
        .padding(.top, PlanteriorSpacing.section)
        .accessibilityIdentifier("collection.empty")
    }
}
