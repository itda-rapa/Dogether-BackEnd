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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardPostReactionQueryService {

    private static final String LIKE = BoardPostReactionType.LIKE.name();

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
        boolean reactedByMe = activePets.findActivePet(userId)
                .map(activePet -> reactions.findReactedPostIds(
                        activePet.petId(),
                        List.of(postId),
                        LIKE
                ).contains(postId))
                .orElse(false);
        return new BoardPostReactionSnapshot(reactionCount, reactedByMe);
    }

    @Transactional(readOnly = true)
    public Map<Long, BoardPostReactionSnapshot> findForPosts(
            Long userId,
            Collection<Long> postIds
    ) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = counts(postIds);
        Set<Long> reactedPostIds = activePets.findActivePet(userId)
                .<Set<Long>>map(activePet -> new HashSet<>(reactions.findReactedPostIds(
                        activePet.petId(),
                        postIds,
                        LIKE
                )))
                .orElseGet(Set::of);
        Map<Long, BoardPostReactionSnapshot> result = new HashMap<>();
        for (Long postId : postIds) {
            result.put(postId, new BoardPostReactionSnapshot(
                    counts.getOrDefault(postId, 0L),
                    reactedPostIds.contains(postId)
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public long countForPost(Long postId) {
        return reactions.countForPost(postId, LIKE);
    }

    private Map<Long, Long> counts(Collection<Long> postIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (BoardPostReactionRepository.PostReactionCount count
                : reactions.countForPosts(postIds, LIKE)) {
            counts.put(count.getPostId(), count.getReactionCount());
        }
        return counts;
    }
}
