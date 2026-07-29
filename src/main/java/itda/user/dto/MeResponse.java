package itda.user.dto;

import itda.user.domain.AccountStatus;
import itda.user.domain.Role;
import itda.user.domain.User;

public record MeResponse(
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
                user.getActivePetId()
        );
    }
}
