package itda.meetingverification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Do not include this request object in logs or events: it carries a short-lived secret. */
public record ConfirmationCodeVerifyRequest(
        @NotBlank @Pattern(regexp = "\\d{4}") String code
) {
}
