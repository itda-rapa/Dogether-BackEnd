package itda.block.service;

import itda.block.repository.UserBlockRepository;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reusable read-only service that checks whether a block relationship exists
 * between two users in either direction.
 */
@Service
public class BlockRelationshipQueryService {

    private final UserBlockRepository userBlockRepository;

    public BlockRelationshipQueryService(UserBlockRepository userBlockRepository) {
        this.userBlockRepository = userBlockRepository;
    }

    /**
     * Returns {@code true} when a block exists from {@code userA} to {@code userB}
     * OR from {@code userB} to {@code userA}.
     */
    @Transactional(readOnly = true)
    public boolean existsBlockBetween(Long userA, Long userB) {
        if (userA == null || userB == null) {
            return false;
        }
        return userBlockRepository.existsBlockBetween(userA, userB);
    }

    /**
     * Returns {@code true} when {@code blockerUserId} has blocked {@code blockedUserId}.
     */
    @Transactional(readOnly = true)
    public boolean isBlockedBy(Long blockerUserId, Long blockedUserId) {
        if (blockerUserId == null || blockedUserId == null) {
            return false;
        }
        return userBlockRepository.existsByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId);
    }

    @Transactional(readOnly = true)
    public Set<Long> findBlockedUserIdsBetween(
            Long viewerUserId,
            Collection<Long> authorUserIds
    ) {
        if (viewerUserId == null || authorUserIds == null || authorUserIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(userBlockRepository.findRelatedUserIds(viewerUserId, authorUserIds));
    }
}
