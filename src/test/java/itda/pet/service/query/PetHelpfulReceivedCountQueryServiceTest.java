package itda.pet.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.boardpost.repository.BoardPostReactionRepository;
import itda.comment.repository.BoardPostCommentReactionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PetHelpfulReceivedCountQueryServiceTest {

    @Mock private BoardPostReactionRepository postReactions;
    @Mock private BoardPostCommentReactionRepository commentReactions;

    @Test
    void mergesPostAndCommentHelpfulCountsByReceiverPetInTwoBatchQueries() {
        List<Long> petIds = List.of(10L, 20L, 30L);
        given(postReactions.countHelpfulReceivedForPets(petIds)).willReturn(List.of(
                postCount(10L, 2), postCount(20L, 1)
        ));
        given(commentReactions.countHelpfulReceivedForPets(petIds)).willReturn(List.of(
                commentCount(10L, 3), commentCount(30L, 4)
        ));

        Map<Long, Long> result = service().countForPets(petIds);

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(10L, 5L, 20L, 1L, 30L, 4L));
        then(postReactions).should().countHelpfulReceivedForPets(petIds);
        then(commentReactions).should().countHelpfulReceivedForPets(petIds);
    }

    @Test
    void emptyReceiverSetDoesNotIssueDatabaseQueries() {
        assertThat(service().countForPets(List.of())).isEmpty();

        then(postReactions).shouldHaveNoInteractions();
        then(commentReactions).shouldHaveNoInteractions();
    }

    @Test
    void singlePetUsesTheSameAggregateAndDefaultsToZero() {
        given(postReactions.countHelpfulReceivedForPets(List.of(10L))).willReturn(List.of());
        given(commentReactions.countHelpfulReceivedForPets(List.of(10L))).willReturn(List.of());

        assertThat(service().countForPet(10L)).isZero();
    }

    private PetHelpfulReceivedCountQueryService service() {
        return new PetHelpfulReceivedCountQueryService(postReactions, commentReactions);
    }

    private BoardPostReactionRepository.PetHelpfulReceivedCount postCount(long petId, long count) {
        return new BoardPostReactionRepository.PetHelpfulReceivedCount() {
            @Override public Long getPetId() { return petId; }
            @Override public long getHelpfulReceivedCount() { return count; }
        };
    }

    private BoardPostCommentReactionRepository.PetHelpfulReceivedCount commentCount(long petId, long count) {
        return new BoardPostCommentReactionRepository.PetHelpfulReceivedCount() {
            @Override public Long getPetId() { return petId; }
            @Override public long getHelpfulReceivedCount() { return count; }
        };
    }
}
