package itda.meetingverification.dto;

import itda.meetingverification.domain.MeetingVerificationMethod;
import java.time.Instant;

public record ConfirmationCodeResult(
        long cardId,
        String status,
        Long meetingId,
        MeetingVerificationMethod verificationMethod,
        Instant confirmedAt
) {
}
