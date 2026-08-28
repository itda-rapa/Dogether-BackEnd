package itda.meetingreview.dto;

import itda.chat.dto.response.CursorPage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 내 Active Pet 발자국 목록 응답(04_M3_API_상세명세.md §11 GET /footprints).
 */
public record FootprintListResponse(
        List<FootprintItem> items,
        CursorPage page
) {

    public record FootprintItem(
            Long footprintId,
            Long meetingId,
            CounterpartPet counterpartPet,
            LocalDate earnedDate,
            Instant createdAt
    ) {
    }

    public record CounterpartPet(
            Long petId,
            String nickname
    ) {
    }
}
