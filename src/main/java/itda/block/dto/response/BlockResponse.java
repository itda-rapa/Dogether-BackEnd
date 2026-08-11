package itda.block.dto.response;

import java.time.Instant;

public record BlockResponse(
        Long blockId,
        Long blockedUserId,
        String blockedUserPublicTag,
        Instant createdAt
) {}