package itda.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

public record SignupRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(min = 10, max = 128)
        String password,

        @NotBlank
        @Size(min = 2, max = 20)
        String nickname,

        @NotBlank
        @Size(max = 20)
        String neighborhoodCode,

        @NotBlank
        @Size(min = 20, max = 256)
        String verificationToken,

        @JsonDeserialize(using = StrictBigDecimalDeserializer.class)
        @DecimalMin("1.00")
        @DecimalMax("500.00")
        @Digits(integer = 3, fraction = 2)
        @Schema(
                description = "사용자 체중(kg). JSON 숫자만 허용하며 소수 둘째 자리까지 입력할 수 있습니다.",
                types = {"number", "null"},
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                minimum = "1.00",
                maximum = "500.00",
                example = "72.50"
        )
        BigDecimal weightKg
) {

    public SignupRequest {
        email = email == null ? null : email.trim();
        nickname = nickname == null ? null : nickname.trim();
        neighborhoodCode = neighborhoodCode == null
                ? null
                : neighborhoodCode.trim();
    }

    public SignupRequest(
            String email,
            String password,
            String nickname,
            String neighborhoodCode,
            String verificationToken
    ) {
        this(email, password, nickname, neighborhoodCode, verificationToken, null);
    }
}
