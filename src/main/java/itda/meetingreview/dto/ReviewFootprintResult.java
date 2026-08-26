package itda.meetingreview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 후기 저장 결과의 발자국 부분(04_M3_API_상세명세.md §11 {@code data.footprint}).
 *
 * @param granted 이 요청 처리로 새 발자국 행이 생겼는가
 * @param footprintId 관련 발자국 id(새로 만든 것 또는 그날 이미 있던 기존 것)
 * @param duplicateDay 이미 그날(Asia/Seoul) 발자국이 있어 새 발자국을 만들지 않았는가
 * @param earnedDate Asia/Seoul 기준 적립 날짜
 */
public record ReviewFootprintResult(
        @Schema(description = "이번 HTTP 요청이 새 Footprint 행을 INSERT했는가. 멱등 replay나 같은 날 기존 발자국 재사용이면 false")
        boolean granted,
        @Schema(description = "관련 발자국 ID. 새로 만든 행 또는 같은 날 재사용한 기존 행")
        Long footprintId,
        @Schema(description = "같은 Asia/Seoul 날짜의 기존 발자국을 재사용했는가")
        boolean duplicateDay,
        @Schema(description = "Asia/Seoul 기준 발자국 적립 날짜")
        LocalDate earnedDate
) {
}
