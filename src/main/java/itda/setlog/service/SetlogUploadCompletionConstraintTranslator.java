package itda.setlog.service;

import java.util.Locale;
import java.util.Set;

final class SetlogUploadCompletionConstraintTranslator {

    private static final int MAX_CAUSE_DEPTH = 12;
    private static final Set<String> COMPLETION_RACE_CONSTRAINTS = Set.of(
            "uk_setlogs_media",
            "uk_setlog_uploads_media",
            "uk_setlog_uploads_setlog"
    );

    private SetlogUploadCompletionConstraintTranslator() {
    }

    static boolean isCompletionRace(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0;
                current != null && depth < MAX_CAUSE_DEPTH;
                depth++) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (COMPLETION_RACE_CONSTRAINTS.stream()
                        .anyMatch(normalized::contains)) {
                    return true;
                }
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }
}
