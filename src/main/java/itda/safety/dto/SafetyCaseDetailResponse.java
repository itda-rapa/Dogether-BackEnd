package itda.safety.dto;

import java.util.List;

public record SafetyCaseDetailResponse(
        SafetyCaseResponse safetyCase,
        List<SafetySignalResponse> recentSignals,
        boolean hasMoreSignals,
        List<SafetyCaseActionResponse> actions
) {
}
