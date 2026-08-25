package itda.safety.dto;

import itda.chat.dto.response.CursorPage;
import java.util.List;

public record SafetyCasePageResponse(
        List<SafetyCaseResponse> items,
        CursorPage page
) {
}
