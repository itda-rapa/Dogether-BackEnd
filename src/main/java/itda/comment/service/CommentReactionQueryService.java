package itda.comment.service;

import itda.comment.domain.CommentReactionType;
import itda.comment.dto.CommentReactionSnapshot;
import itda.comment.repository.BoardPostCommentReactionRepository;
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
public class CommentReactionQueryService {

    private static final String HELPFUL = CommentReactionType.HELPFUL.name();

    private final BoardPostCommentReactionRepository reactions;
    private final ActivePetQueryService activePets;

    public CommentReactionQueryService(
            BoardPostCommentReactionRepository reactions,
            ActivePetQueryService activePets
    ) {
        this.reactions = reactions;
        this.activePets = activePets;
    }

    @Transactional(readOnly = true)
    public Map<Long, CommentReactionSnapshot> findForComments(
            Long userId,
            Collection<Long> commentIds
    ) {
        if (commentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (BoardPostCommentReactionRepository.CommentReactionCount count
                : reactions.countForComments(commentIds, HELPFUL)) {
            counts.put(count.getCommentId(), count.getReactionCount());
        }
        Set<Long> reactedIds = activePets.findActivePet(userId)
                .<Set<Long>>map(activePet -> new HashSet<>(reactions.findReactedCommentIds(
                        activePet.petId(), commentIds, HELPFUL)))
                .orElseGet(Set::of);
        Map<Long, CommentReactionSnapshot> result = new HashMap<>();
        for (Long commentId : commentIds) {
            result.put(commentId, new CommentReactionSnapshot(
                    counts.getOrDefault(commentId, 0L),
                    reactedIds.contains(commentId)
            ));
        }
        return result;
    }
}
