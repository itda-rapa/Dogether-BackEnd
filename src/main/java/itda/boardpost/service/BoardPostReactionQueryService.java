package itda.boardpost.service;

import itda.boardpost.domain.BoardPostReactionType;
import itda.boardpost.dto.BoardPostReactionSnapshot;
import itda.boardpost.repository.BoardPostReactionRepository;
import itda.pet.service.query.ActivePetQueryService;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardPostReactionQueryService {

    private static final String LIKE = BoardPostReactionType.LIKE.name();
    private static final String HELPFUL = BoardPostReactionType.HELPFUL.name();

    private final BoardPostReactionRepository reactions;
    private final ActivePetQueryService activePets;

    public BoardPostReactionQueryService(
            BoardPostReactionRepository reactions,
            ActivePetQueryService activePets
    ) {
        this.reactions = reactions;
        this.activePets = activePets;
    }

    @Transactional(readOnly = true)
    public BoardPostReactionSnapshot findForPost(Long userId, Long postId) {
        long reactionCount = reactions.countForPost(postId, LIKE);
        long helpfulCount = reactions.countForPost(postId, HELPFUL);
        Optional<Long> activePetId = activePetId(userId);
        Set<Long> liked = reactedPostIds(activePetId, List.of(postId), LIKE);
        Set<Long> helpful = reactedPostIds(activePetId, List.of(postId), HELPFUL);
        return new BoardPostReactionSnapshot(
                reactionCount,
                liked.contains(postId),
                helpfulCount,
                helpful.contains(postId)
        );
    }

    @Transactional(readOnly = true)
    public Map<Long, BoardPostReactionSnapshot> findForPosts(
            Long userId,
            Collection<Long> postIds
    ) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = counts(postIds, LIKE);
        Map<Long, Long> helpfulCounts = counts(postIds, HELPFUL);
        Optional<Long> activePetId = activePetId(userId);
        Set<Long> reactedPostIds = reactedPostIds(activePetId, postIds, LIKE);
        Set<Long> helpfulPostIds = reactedPostIds(activePetId, postIds, HELPFUL);
        Map<Long, BoardPostReactionSnapshot> result = new HashMap<>();
        for (Long postId : postIds) {
            result.put(postId, new BoardPostReactionSnapshot(
                    counts.getOrDefault(postId, 0L),
                    reactedPostIds.contains(postId),
                    helpfulCounts.getOrDefault(postId, 0L),
                    helpfulPostIds.contains(postId)
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public long countForPost(Long postId) {
        return reactions.countForPost(postId, LIKE);
    }

    private Optional<Long> activePetId(Long userId) {
        return activePets.findActivePet(userId).map(activePet -> activePet.petId());
    }

    private Set<Long> reactedPostIds(
            Optional<Long> activePetId,
            Collection<Long> postIds,
            String type
    ) {
        return activePetId
                .<Set<Long>>map(petId -> new HashSet<>(reactions.findReactedPostIds(
                        petId, postIds, type)))
                .orElseGet(Set::of);
    }

    private Map<Long, Long> counts(Collection<Long> postIds, String type) {
        Map<Long, Long> counts = new HashMap<>();
        for (BoardPostReactionRepository.PostReactionCount count
                : reactions.countForPosts(postIds, type)) {
            counts.put(count.getPostId(), count.getReactionCount());
        }
        return counts;
    }
}
