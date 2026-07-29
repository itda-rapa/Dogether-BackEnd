package itda.friend.service;

import itda.friend.repository.FriendRequestRepository;
import itda.friend.repository.FriendshipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes current Pet relationships when two Users become blocked.
 *
 * <p>The block itself is User-level, so cleanup covers every Pet owned by either User rather
 * than only the source and target Pets that were selected in the UI.
 */
@Service
@RequiredArgsConstructor
public class FriendBlockCleanupService {

    private final FriendshipRepository friendshipRepository;
    private final FriendRequestRepository friendRequestRepository;

    @Transactional
    public CleanupResult cleanupBetweenUsers(Long userA, Long userB) {
        int deletedFriendships = friendshipRepository.deleteBetweenUsers(userA, userB);
        int canceledPendingRequests =
                friendRequestRepository.cancelPendingBetweenUsers(userA, userB);
        return new CleanupResult(deletedFriendships, canceledPendingRequests);
    }

    public record CleanupResult(
            int deletedFriendships,
            int canceledPendingRequests
    ) {}
}
