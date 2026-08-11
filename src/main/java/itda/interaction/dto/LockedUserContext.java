package itda.interaction.dto;

import itda.user.domain.AccountStatus;

public record LockedUserContext(
        Long userId,
        AccountStatus accountStatus,
        Long activePetId,
        String publicTag
) {
}
