import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

private enum PlantCollectionAttention {
    static let foreground = Color(
        red: 1,
        green: 77.0 / 255.0,
        blue: 79.0 / 255.0
    )
    static let background = Color(
        red: 1,
        green: 232.0 / 255.0,
        blue: 232.0 / 255.0
    )
}

extension PlantCollectionView {
    var plantRows: some View {
        VStack(spacing: 10) {
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
        let presentedName = PlantCarePresentation.collectionName(
            for: identity,
            fallback: item.element.displayName
        )
        return NavigationLink {
            PlantCareDetailView(index: item.offset)
        } label: {
            HStack(spacing: PlanteriorSpacing.medium) {
                Image(PlantCarePresentation.asset(for: identity))
                    .resizable()
                    .scaledToFill()
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
                    .accessibilityLabel("\(presentedName) 이미지")
                    .accessibilityIdentifier("collection.image.\(identity)")
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    HStack(spacing: PlanteriorSpacing.small) {
                        Text(presentedName)
                            .font(PlanteriorTypography.cardTitle)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                        Circle()
                            .fill(
                                status.needsAttention
                                    ? PlantCollectionAttention.foreground
                                    : PlanteriorPalette.accent.color
                            )
                            .frame(width: 6, height: 6)
                            .accessibilityHidden(true)
                    }
                    careStatusPill(status, index: item.offset)
                }
                Spacer(minLength: PlanteriorSpacing.small)
                Image(systemName: "chevron.right")
                    .foregroundStyle(PlanteriorPalette.textTertiary.color)
                    .accessibilityHidden(true)
            }
            .padding(PlanteriorSpacing.medium)
            .background(PlanteriorPalette.surface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                    .stroke(
                        PlanteriorPalette.border.color,
                        lineWidth: PlanteriorControl.hairline
                    )
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("collection.row.\(item.offset)")
        .accessibilityLabel(presentedName)
        .accessibilityValue(status.title)
        .onAppear { collection.rememberScrollAnchor(item.offset) }
    }

    var trueEmptyState: some View {
        VStack(spacing: 0) {
            Image(.collectionEmptyAvatar)
                .resizable()
                .scaledToFit()
                .frame(
                    width: sizeCategory.isAccessibilityCategory ? 96 : 140,
                    height: sizeCategory.isAccessibilityCategory ? 96 : 140
                )
                .padding(sizeCategory.isAccessibilityCategory ? 0 : 20)
                .background(PlanteriorPalette.subtle.color)
                .clipShape(Circle())
                .accessibilityLabel("빈 화분 캐릭터")
                .accessibilityIdentifier("collection.empty.illustration")
            Text("아직 등록된 식물이 없어요 🥺")
                .font(PlanteriorTypography.sectionTitle)
                .padding(.top, sizeCategory.isAccessibilityCategory ? 8 : 28)
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
            .padding(.top, PlanteriorSpacing.small)
            .accessibilityIdentifier("collection.empty.body")
            Button(action: openCamera) {
                Label("사진으로 식별하기", systemImage: "camera")
                    .font(PlanteriorTypography.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                    .background(PlanteriorPalette.accent.color)
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
            }
            .buttonStyle(.plain)
            .padding(.top, 28)
            .accessibilityIdentifier("collection.empty.camera")
            NavigationLink {
                PlantRegistrationView()
            } label: {
                Label("직접 등록하기", systemImage: "square.and.pencil")
                    .font(PlanteriorTypography.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .background(PlanteriorPalette.surface.color)
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
                    .overlay {
                        RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                            .stroke(PlanteriorPalette.accent.color, lineWidth: 1)
                    }
            }
            .padding(.top, PlanteriorSpacing.medium)
            .accessibilityIdentifier("collection.empty.manual")
        }
        .padding(.horizontal, PlanteriorSpacing.small)
        .padding(
            .top,
            sizeCategory.isAccessibilityCategory ? 0 : 85
        )
    }

    @ViewBuilder
    private func careStatusPill(
        _ status: (
            title: String,
            variant: PlanteriorStatusVariant,
            needsAttention: Bool
        ),
        index: Int
    ) -> some View {
        if status.needsAttention {
            Text(status.title)
                .font(PlanteriorTypography.microLabel)
                .foregroundStyle(PlantCollectionAttention.foreground)
                .padding(.horizontal, PlanteriorSpacing.medium)
                .padding(.vertical, PlanteriorSpacing.extraSmall)
                .background(PlantCollectionAttention.background)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.small))
                .accessibilityIdentifier("collection.status.\(index)")
                .accessibilityValue("주의")
        } else {
            PlanteriorStatusPill(
                LocalizedStringKey(status.title),
                variant: status.variant
            )
            .accessibilityIdentifier("collection.status.\(index)")
        }
    }

    func careStatus(
        for item: (offset: Int, element: PlantRegistrationDraft)
    ) -> (
        title: String,
        variant: PlanteriorStatusVariant,
        needsAttention: Bool
    ) {
        guard let today else { return ("일정 확인 필요", .neutral, false) }
        switch collection.wateringStatus(
            at: item.offset,
            lastWateredOn: item.element.lastWateredOn,
            today: today,
            intervalDays: collection.wateringIntervalDays(at: item.offset)
        ) {
        case .unavailable:
            return ("물 주기 미설정", .neutral, false)
        case .overdue:
            return ("물주기 지연", .warning, false)
        case .due:
            return ("오늘 물주기!", .warning, true)
        case let .upcoming(nextDate):
            return ("D-\(max(daysBetween(today, nextDate), 0))", .neutral, false)
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
