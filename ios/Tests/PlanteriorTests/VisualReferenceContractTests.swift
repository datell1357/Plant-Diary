@testable import Planterior
import Testing
import UIKit

struct InventoryVisualReferenceContractTests {
    @Test
    func inventoryReferencePresentationIsDeterministicAndResponsive() {
        #expect(
            InventoryReferenceMetrics.contentTopCorrection(
                measuredSafeAreaTop: 62
            ) == -14.5
        )
        #expect(
            InventoryReferenceMetrics.contentTopCorrection(
                measuredSafeAreaTop: 59
            ) == -11.5
        )
        #expect(
            InventoryReferenceMetrics.contentTopCorrection(
                measuredSafeAreaTop: 47.5
            ) == 0
        )
        #expect(InventoryMode.shop.headerAction.systemImage == "shippingbox")
        #expect(InventoryMode.shop.headerAction.identifier == "storage.mode.warehouse")
        #expect(InventoryMode.warehouse.headerAction.systemImage == "archivebox")
        #expect(InventoryMode.warehouse.headerAction.identifier == "storage.mode.shop")
        #expect(
            InventoryReferenceMetrics.shopGridRowSpacing(
                scrollBodyHeight: 778
            ) == 12
        )
        #expect(
            InventoryReferenceMetrics.shopGridRowSpacing(
                scrollBodyHeight: 748
            ) == 8
        )
        #expect(
            !InventoryDetailFavoritePresentation.initialState(
                environment: ["QA_INVENTORY_FIXTURE": "1"],
                isDebug: true
            )
        )
        #expect(
            !InventoryDetailFavoritePresentation.initialState(
                environment: [:],
                isDebug: true
            )
        )
        #expect(
            !InventoryDetailFavoritePresentation.initialState(
                environment: ["QA_INVENTORY_FIXTURE": "1"],
                isDebug: false
            )
        )
    }
}

struct VisualReferenceContractTests {
    @Test @MainActor
    func quietHoursWarningMatchesLocalizedReferenceAnatomy() {
        #expect(
            SettingsWarningCard.localizedCopy
                == "태풍, 한파, 폭염\u{00A0}등 식물 생존에 직접적 영향을 미치는 "
                + "기상 특보 및 재난 알림은 시간 설정과 관계없이 즉시 발송됩니다."
        )
        #expect(!SettingsWarningCard.referenceVisualCopy.contains("\n"))
        #expect(
            SettingsWarningCard.referenceVisualCopy.replacingOccurrences(
                of: KoreanTypography.wordJoiner,
                with: ""
            ) == SettingsWarningCard.localizedCopy
        )
        for width: CGFloat in [304, 292] {
            let lines = KoreanTypography.visualLines(
                in: SettingsWarningCard.referenceVisualCopy,
                font: .systemFont(ofSize: 13, weight: .bold),
                width: width
            )
            #expect(lines.contains { $0.contains("기상") })
            #expect(lines.contains { $0.contains("발송됩니다.") })
        }
        #expect(SettingsReferenceMetrics.warningIconWidth == 18)
        #expect(SettingsReferenceMetrics.warningContentSpacing == 8)
        let warningTextMeasure: CGFloat = 362
            - (2 * SettingsReferenceMetrics.warningContentInset)
            - SettingsReferenceMetrics.warningIconWidth
            - SettingsReferenceMetrics.warningContentSpacing
        #expect(warningTextMeasure == 304)
        #expect(SettingsReferenceMetrics.warningHeight == 80)
    }

    @Test
    func settingsProfileUsesReferenceAvatarGlyph() {
        #expect(SettingsReferenceMetrics.profileAvatarGlyph == "🌿")
    }

    @Test @MainActor
    func liveSpeciesBoundariesJoinVisualScalarsAndPreserveCleanAXScalars() {
        let homeOriginal = "초록 요정 (미니 선인장)"
        let identificationOriginal = "몬스테라 델리시오사"
        let detailOriginal = PlantCarePresentation.species(
            for: "몬몬이 (몬스테라)"
        )

        let homeVisual = KoreanTypography.atomicParentheticalSpecies(in: homeOriginal)
        let identificationVisual = KoreanTypography.atomic(identificationOriginal)
        let detailVisual = KoreanTypography.atomic(detailOriginal)
        let homeExpected = "초록 요정 ("
            + "미\u{2060}니 선\u{2060}인\u{2060}장)"
        let identificationExpected = "몬\u{2060}스\u{2060}테\u{2060}라 "
            + "델\u{2060}리\u{2060}시\u{2060}오\u{2060}사"
        let detailExpected = "M\u{2060}o\u{2060}n\u{2060}s\u{2060}t\u{2060}e"
            + "\u{2060}r\u{2060}a d\u{2060}e\u{2060}l"
            + "\u{2060}i\u{2060}c\u{2060}i\u{2060}o\u{2060}s\u{2060}a"

        #expect(homeVisual == homeExpected)
        #expect(identificationVisual == identificationExpected)
        #expect(detailVisual == detailExpected)
        #expect(homeVisual.hasPrefix("초록 요정 ("))
        #expect(!homeOriginal.unicodeScalars.contains("\u{2060}"))
        #expect(!identificationOriginal.unicodeScalars.contains("\u{2060}"))
        #expect(!detailOriginal.unicodeScalars.contains("\u{2060}"))
    }

    @Test @MainActor
    func koreanSpeciesAndDependentWarningPhraseStayAtomic() {
        #expect(SettingsWarningCard.accessibilityCopy.contains("폭염\u{00A0}등"))

        let bound = KoreanTypography.binding(
            "가나다라마바사.",
            phrases: ["마바사."]
        )
        let lines = KoreanTypography.visualLines(
            in: bound,
            font: .preferredFont(forTextStyle: .subheadline),
            width: 44
        )
        #expect(lines.contains("마바사."))
        #expect(lines.allSatisfy { $0 != "사." })
    }

    @Test
    func plantGuideMetricsKeepReferenceEmojiAndSemanticValues() {
        let metrics = PlantCarePresentation.guideMetrics

        #expect(metrics.map(\.id) == ["water", "light", "temperature", "humidity"])
        #expect(metrics.map(\.icon) == ["💧", "☀️", "🌡️", "💨"])
        #expect(metrics.map(\.title) == ["물 주기", "햇빛", "온도", "습도"])
        #expect(metrics.map(\.value) == ["7~10일 간격", "밝은 간접광", "18~27°C", "60% 이상"])
    }
}
