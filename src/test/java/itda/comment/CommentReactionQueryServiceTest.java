package itda.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.comment.dto.CommentReactionSnapshot;
import itda.comment.repository.BoardPostCommentReactionRepository;
import itda.comment.service.CommentReactionQueryService;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommentReactionQueryServiceTest {

    @Mock private BoardPostCommentReactionRepository reactions;
    @Mock private ActivePetQueryService activePets;

    @Test
    void emptyInputSkipsEveryRepositoryAndActivePetQuery() {
        assertThat(service().findForComments(1L, List.of())).isEmpty();

        then(reactions).shouldHaveNoInteractions();
        then(activePets).shouldHaveNoInteractions();
    }

    @Test
    void batchUsesOneHelpfulCountAndOneActivePetReactionLookup() {
        List<Long> commentIds = List.of(10L, 20L, 30L);
        given(reactions.countForComments(commentIds, "HELPFUL"))
                .willReturn(List.of(count(10L, 2L), count(30L, 1L)));
        given(activePets.findActivePet(1L))
                .willReturn(Optional.of(new ActivePetContext(7L, 1L, "pet#A1B2", "pet", null, false)));
        given(reactions.findReactedCommentIds(7L, commentIds, "HELPFUL"))
                .willReturn(List.of(20L));

        var result = service().findForComments(1L, commentIds);

        assertThat(result).containsEntry(10L, new CommentReactionSnapshot(2L, false))
                .containsEntry(20L, new CommentReactionSnapshot(0L, true))
                .containsEntry(30L, new CommentReactionSnapshot(1L, false));
        then(reactions).should().countForComments(commentIds, "HELPFUL");
        then(activePets).should().findActivePet(1L);
        then(reactions).should().findReactedCommentIds(7L, commentIds, "HELPFUL");
        then(reactions).shouldHaveNoMoreInteractions();
        then(activePets).shouldHaveNoMoreInteractions();
    }

    private CommentReactionQueryService service() {
        return new CommentReactionQueryService(reactions, activePets);
    }

    private BoardPostCommentReactionRepository.CommentReactionCount count(long commentId, long reactionCount) {
        return new BoardPostCommentReactionRepository.CommentReactionCount() {
            @Override public Long getCommentId() { return commentId; }
            @Override public long getReactionCount() { return reactionCount; }
        };
    }
}
