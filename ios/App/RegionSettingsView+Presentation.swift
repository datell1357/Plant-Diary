import PlanteriorDesignSystem
import SwiftUI

extension RegionSettingsView {
    var searchField: some View {
        HStack(spacing: PlanteriorSpacing.small) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(PlanteriorPalette.textTertiary.color)
                .accessibilityHidden(true)
            TextField("동명(읍/면) 또는 도로명 입력", text: $regionQuery)
                .textInputAutocapitalization(.never)
                .accessibilityIdentifier("weather.manual-region")
            if !regionQuery.isEmpty {
                Button { regionQuery = "" } label: {
                    Image(systemName: "xmark.circle.fill")
                        .frame(
                            width: PlanteriorControl.minimumTarget,
                            height: PlanteriorControl.minimumTarget
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("검색어 지우기")
            }
        }
        .padding(.leading, PlanteriorSpacing.large)
        .frame(height: SettingsReferenceMetrics.regionSearchHeight)
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
            weather.setManualRegion(nil)
            onSaved()
            weather.requestLocationPermission()
        } label: {
            HStack(alignment: .top, spacing: PlanteriorSpacing.small) {
                SettingsLocationGlyph(
                    size: SettingsReferenceMetrics.regionLocationGlyphSize,
                    translation: CGSize(width: 1, height: -1),
                    tailInset: 1.5
                )
                .frame(
                    width: PlanteriorSpacing.large,
                    height: PlanteriorSpacing.extraLarge,
                    alignment: .top
                )
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text("현재 위치로 설정")
                        .font(PlanteriorTypography.cardTitle)
                    Text(currentLocationText)
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .accessibilityIdentifier("weather.current-location-text")
                }
                Spacer(minLength: PlanteriorSpacing.small)
                Image(
                    systemName: usesCurrentLocation
                        ? "checkmark"
                        : "arrow.clockwise"
                )
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityHidden(true)
            }
            .padding(PlanteriorSpacing.large)
            .frame(
                maxWidth: .infinity,
                minHeight: SettingsReferenceMetrics.regionCurrentLocationHeight,
                alignment: .leading
            )
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
        .accessibilityValue(usesCurrentLocation ? "선택됨" : "선택 안 됨")
        .accessibilityIdentifier("weather.use-current-location")
    }

    var regionCard: some View {
        PlanteriorGroupedSurface {
            ForEach(Array(filteredRegions.enumerated()), id: \.element.code) { index, region in
                regionRow(region)
                if index < filteredRegions.count - 1 {
                    Divider()
                }
            }
        }
    }

    func regionRow(_ region: (code: String, name: String)) -> some View {
        let selected = !usesCurrentLocation && selectedCode == region.code
        return Button {
            usesCurrentLocation = false
            selectedCode = region.code
            weather.setManualRegion(region.code)
            onSaved()
        } label: {
            if sizeCategory.isAccessibilityCategory {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    HStack(spacing: PlanteriorSpacing.small) {
                        if selected {
                            Image(systemName: "checkmark")
                                .foregroundStyle(PlanteriorPalette.accent.color)
                                .accessibilityHidden(true)
                        }
                        regionName(region.name, selected: selected)
                        Spacer(minLength: PlanteriorSpacing.small)
                        if !selected {
                            Image(systemName: "xmark.circle")
                                .foregroundStyle(PlanteriorPalette.textTertiary.color)
                                .accessibilityHidden(true)
                        }
                    }
                    if selected {
                        Text("기준 지역")
                            .font(PlanteriorTypography.microLabel)
                            .foregroundStyle(PlanteriorPalette.textSecondary.color)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, PlanteriorSpacing.large)
                .padding(.vertical, PlanteriorSpacing.small)
                .frame(
                    maxWidth: .infinity,
                    minHeight: SettingsReferenceMetrics.regionRowHeight,
                    alignment: .leading
                )
                .contentShape(Rectangle())
            } else {
                HStack(spacing: PlanteriorSpacing.small) {
                    if selected {
                        Image(systemName: "checkmark")
                            .foregroundStyle(PlanteriorPalette.accent.color)
                            .accessibilityHidden(true)
                    }
                    regionName(region.name, selected: selected)
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
                .frame(height: SettingsReferenceMetrics.regionRowHeight)
                .contentShape(Rectangle())
            }
        }
        .buttonStyle(.plain)
        .accessibilityValue(selected ? "선택됨" : "선택 안 됨")
        .accessibilityIdentifier("weather.region-result.\(region.code)")
    }

    private func regionName(_ name: String, selected: Bool) -> some View {
        Text(name)
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
            .fixedSize(horizontal: false, vertical: true)
    }
}
