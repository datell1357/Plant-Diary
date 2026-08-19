import Foundation
import PlanteriorData
import SwiftUI

extension PlantCareDetailView {
    var trimmedNickname: String {
        nickname.trimmingCharacters(in: .whitespacesAndNewlines)
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
