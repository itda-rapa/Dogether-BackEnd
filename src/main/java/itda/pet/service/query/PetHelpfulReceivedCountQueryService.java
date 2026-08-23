package itda.pet.service.query;

import itda.boardpost.repository.BoardPostReactionRepository;
import itda.comment.repository.BoardPostCommentReactionRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetHelpfulReceivedCountQueryService {

    private final BoardPostReactionRepository postReactions;
    private final BoardPostCommentReactionRepository commentReactions;

    public PetHelpfulReceivedCountQueryService(
            BoardPostReactionRepository postReactions,
            BoardPostCommentReactionRepository commentReactions
    ) {
        this.postReactions = postReactions;
        this.commentReactions = commentReactions;
    }

    @Transactional(readOnly = true)
    public long countForPet(Long petId) {
        return countForPets(java.util.List.of(petId)).getOrDefault(petId, 0L);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> countForPets(Collection<Long> petIds) {
        if (petIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (BoardPostReactionRepository.PetHelpfulReceivedCount count
                : postReactions.countHelpfulReceivedForPets(petIds)) {
            counts.merge(count.getPetId(), count.getHelpfulReceivedCount(), Long::sum);
        }
        for (BoardPostCommentReactionRepository.PetHelpfulReceivedCount count
                : commentReactions.countHelpfulReceivedForPets(petIds)) {
            counts.merge(count.getPetId(), count.getHelpfulReceivedCount(), Long::sum);
        }
        return counts;
    }
}
