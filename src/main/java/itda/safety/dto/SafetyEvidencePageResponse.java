package itda.safety.dto;

import itda.chat.dto.response.CursorPage;
import java.util.List;

public record SafetyEvidencePageResponse(
        List<SafetyEvidenceResponse> items,
        CursorPage page
) {
}
