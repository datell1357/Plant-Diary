import PlanteriorDesignSystem
import SwiftUI

extension RegionSettingsView {
    var searchField: some View {
        HStack(spacing: PlanteriorSpacing.medium) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(PlanteriorPalette.textTertiary.color)
                .accessibilityHidden(true)
            TextField("동명(읍/면) 또는 도로명 입력", text: $regionQuery)
                .textInputAutocapitalization(.never)
                .accessibilityIdentifier("weather.manual-region")
            if !regionQuery.isEmpty {
                Button { regionQuery = "" } label: {
                    Image(systemName: "xmark.circle.fill")
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("검색어 지우기")
            }
        }
        .padding(.leading, PlanteriorSpacing.large)
        .frame(minHeight: 48)
        .background(PlanteriorPalette.surface.color)
        .clipShape(Capsule())
        .overlay {
            Capsule().stroke(
                PlanteriorPalette.border.color,
                lineWidth: PlanteriorControl.hairline
            )
        }
    }

    var currentLocationCard: some View {
        Button {
            usesCurrentLocation = true
            selectedCode = nil
            weather.requestLocationPermission()
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: PlanteriorSpacing.medium) {
                PlanteriorIconWell(systemImage: "location.fill")
                    .alignmentGuide(.firstTextBaseline) { dimensions in
                        dimensions[VerticalAlignment.center]
                    }
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text("현재 위치로 설정")
                        .font(PlanteriorTypography.cardTitle)
                    Text(currentLocationText)
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                }
                Spacer(minLength: PlanteriorSpacing.small)
                Image(
                    systemName: usesCurrentLocation
                        ? "checkmark"
                        : "arrow.clockwise"
                )
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            }
            .padding(PlanteriorSpacing.large)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .background(PlanteriorPalette.surface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                    .stroke(PlanteriorPalette.border.color, lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityValue(usesCurrentLocation ? "선택됨" : "선택 안 됨")
        .accessibilityIdentifier("weather.use-current-location")
    }

    var regionCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(filteredRegions.enumerated()), id: \.element.code) { index, region in
                regionRow(region)
                if index < filteredRegions.count - 1 {
                    Divider().padding(.leading, PlanteriorSpacing.large)
                }
            }
        }
        .background(PlanteriorPalette.surface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                .stroke(PlanteriorPalette.border.color, lineWidth: 1)
        }
    }

    func regionRow(_ region: (code: String, name: String)) -> some View {
        let selected = !usesCurrentLocation && selectedCode == region.code
        return Button {
            usesCurrentLocation = false
            selectedCode = region.code
        } label: {
            HStack(spacing: PlanteriorSpacing.medium) {
                if selected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(PlanteriorPalette.accent.color)
                        .accessibilityHidden(true)
                }
                Text(region.name)
                    .font(
                        PlanteriorTypography.supporting.weight(
                            selected ? .semibold : .regular
                        )
                    )
                    .foregroundStyle(
                        selected
                            ? PlanteriorPalette.accent.color
                            : PlanteriorPalette.textPrimary.color
                    )
                Spacer(minLength: PlanteriorSpacing.small)
                if selected {
                    Text("기준 지역")
                        .font(PlanteriorTypography.microLabel)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                } else {
                    Image(systemName: "xmark.circle")
                        .foregroundStyle(PlanteriorPalette.textTertiary.color)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, PlanteriorSpacing.large)
            .frame(minHeight: PlanteriorControl.rowHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(selected ? "선택됨" : "선택 안 됨")
        .accessibilityIdentifier("weather.region-result.\(region.code)")
    }
}
