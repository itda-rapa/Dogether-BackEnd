package itda.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import itda.user.domain.AccountStatus;
import itda.user.domain.Role;
import itda.user.domain.User;
import java.math.BigDecimal;

public record MeResponse(
        Long userId,
        String email,
        String nickname,
        String publicTag,
        Role role,
        AccountStatus accountStatus,
        AccessLevel accessLevel,
        String neighborhoodCode,
        Long activePetId,
        @Schema(types = {"number", "null"}, nullable = true)
        BigDecimal weightKg
) {

    public MeResponse(
            Long userId,
            String email,
            String nickname,
            String publicTag,
            Role role,
            AccountStatus accountStatus,
            AccessLevel accessLevel,
            String neighborhoodCode,
            Long activePetId
    ) {
        this(
                userId,
                email,
                nickname,
                publicTag,
                role,
                accountStatus,
                accessLevel,
                neighborhoodCode,
                activePetId,
                null
        );
    }

    public static MeResponse from(User user) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getPublicTag(),
                user.getRole(),
                user.getAccountStatus(),
                user.hasActivePet() ? AccessLevel.L2 : AccessLevel.L1,
                user.getNeighborhoodCode(),
                user.getActivePetId(),
                user.getWeightKg()
        );
    }
}
