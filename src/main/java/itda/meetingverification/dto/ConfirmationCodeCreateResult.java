package itda.meetingverification.dto;

import java.time.Instant;

/** 평문 code는 이 발급 응답에서만 존재한다. */
public record ConfirmationCodeCreateResult(String code, Instant expiresAt) {
}
