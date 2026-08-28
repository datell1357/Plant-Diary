import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

private struct PlantCollectionRowPresentation {
    let index: Int
    let identity: String
    let name: String
    let status: PlantCareStatus
}

extension PlantCollectionView {
    var plantRows: some View {
        VStack(spacing: CollectionReferenceMetrics.rowSpacing) {
            ForEach(filteredPlants, id: \.offset) { item in
                plantRow(item)
            }
        }
    }

    func plantRow(
        _ item: (offset: Int, element: PlantRegistrationDraft)
    ) -> some View {
        let presentation = rowPresentation(for: item)
        return NavigationLink {
            PlantCareDetailView(index: item.offset)
        } label: {
            plantRowContent(presentation)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("collection.row.\(item.offset)")
        .accessibilityLabel(presentation.name)
        .accessibilityValue(presentation.status.title)
        .onAppear { collection.rememberScrollAnchor(item.offset) }
    }

    private func rowPresentation(
        for item: (offset: Int, element: PlantRegistrationDraft)
    ) -> PlantCollectionRowPresentation {
        let identity = collection.presentationIdentity(at: item.offset)
            ?? "draft-\(item.element.displayName)"
        return PlantCollectionRowPresentation(
            index: item.offset,
            identity: identity,
            name: PlantCarePresentation.collectionName(
                for: identity,
                fallback: item.element.displayName
            ),
            status: careStatus(for: item)
        )
    }

    private func plantRowContent(
        _ presentation: PlantCollectionRowPresentation
    ) -> some View {
        HStack(spacing: PlanteriorSpacing.medium) {
            Image(PlantCarePresentation.asset(for: presentation.identity))
                .resizable()
                .scaledToFill()
                .frame(
                    width: CollectionReferenceMetrics.rowImageSide,
                    height: CollectionReferenceMetrics.rowImageSide
                )
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
                .accessibilityLabel("\(presentation.name) 이미지")
                .accessibilityIdentifier("collection.image.\(presentation.identity)")
            VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                HStack(spacing: PlanteriorSpacing.small) {
                    plantNameLabel(
                        presentation.name,
                        index: presentation.index
                    )
                    Circle()
                        .fill(PlanteriorPalette.collectionStatus.color)
                        .frame(
                            width: CollectionReferenceMetrics.statusDotSide,
                            height: CollectionReferenceMetrics.statusDotSide
                        )
                        .accessibilityHidden(true)
                }
                careStatusPill(
                    presentation.status,
                    index: presentation.index
                )
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
                .strokeBorder(
                    PlanteriorPalette.border.color,
                    lineWidth: PlanteriorControl.hairline
                )
        }
    }

    @ViewBuilder
    private func plantNameLabel(
        _ presentedName: String,
        index: Int
    ) -> some View {
        let parts = KoreanTypography.parentheticalSpeciesParts(
            in: presentedName
        )
        if sizeCategory.isAccessibilityCategory, let parts {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.none) {
                Text(parts.leading)
                    .accessibilityLabel(presentedName)
                    .accessibilityIdentifier("collection.name.\(index)")
                Text(KoreanTypography.atomic(parts.parenthetical))
                    .lineLimit(1)
                    .minimumScaleFactor(
                        CollectionReferenceMetrics.atomicSpeciesMinimumScale
                    )
                    .accessibilityLabel(parts.parenthetical)
                    .accessibilityIdentifier("collection.species.\(index)")
            }
            .font(PlanteriorTypography.cardTitle)
            .layoutPriority(1)
        } else {
            Text(
                KoreanTypography.atomicParentheticalSpecies(
                    in: presentedName
                )
            )
            .font(PlanteriorTypography.cardTitle)
            .accessibilityLabel(presentedName)
            .lineLimit(1)
            .minimumScaleFactor(
                CollectionReferenceMetrics.collectionNameMinimumScale
            )
            .layoutPriority(1)
            .accessibilityIdentifier("collection.name.\(index)")
        }
    }
}
