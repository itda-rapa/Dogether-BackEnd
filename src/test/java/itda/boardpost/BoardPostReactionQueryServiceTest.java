package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.boardpost.repository.BoardPostReactionRepository;
import itda.boardpost.service.BoardPostReactionQueryService;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BoardPostReactionQueryServiceTest {

    @Mock private BoardPostReactionRepository reactions;
    @Mock private ActivePetQueryService activePets;

    @Test
    void feedBatchUsesExactlyOneCountAndOneActivePetReactionQuery() {
        BoardPostReactionQueryService service = service();
        List<Long> postIds = List.of(101L, 102L, 103L);
        ActivePetContext activePet = new ActivePetContext(7L, 1L, "pet#A1B2", "pet", null, false);
        given(reactions.countForPosts(postIds, "LIKE")).willReturn(List.of(
                count(101L, 2), count(103L, 1)
        ));
        given(activePets.findActivePet(1L)).willReturn(Optional.of(activePet));
        given(reactions.findReactedPostIds(7L, postIds, "LIKE")).willReturn(List.of(101L));

        var result = service.findForPosts(1L, postIds);

        assertThat(result).containsEntry(101L, new itda.boardpost.dto.BoardPostReactionSnapshot(2, true))
                .containsEntry(102L, new itda.boardpost.dto.BoardPostReactionSnapshot(0, false))
                .containsEntry(103L, new itda.boardpost.dto.BoardPostReactionSnapshot(1, false));
        then(reactions).should().countForPosts(postIds, "LIKE");
        then(reactions).should().findReactedPostIds(7L, postIds, "LIKE");
        then(reactions).shouldHaveNoMoreInteractions();
    }

    @Test
    void l1FeedReturnsActualCountsButSkipsMyReactionQuery() {
        BoardPostReactionQueryService service = service();
        List<Long> postIds = List.of(101L, 102L);
        given(reactions.countForPosts(postIds, "LIKE")).willReturn(List.of(count(101L, 4)));
        given(activePets.findActivePet(1L)).willReturn(Optional.empty());

        var result = service.findForPosts(1L, postIds);

        assertThat(result).containsEntry(101L, new itda.boardpost.dto.BoardPostReactionSnapshot(4, false))
                .containsEntry(102L, new itda.boardpost.dto.BoardPostReactionSnapshot(0, false));
        then(reactions).should().countForPosts(postIds, "LIKE");
        then(reactions).shouldHaveNoMoreInteractions();
    }

    @Test
    void emptyFeedSkipsEveryReactionAndActivePetQuery() {
        assertThat(service().findForPosts(1L, List.of())).isEmpty();

        then(reactions).shouldHaveNoInteractions();
        then(activePets).shouldHaveNoInteractions();
    }

    @Test
    void l1DetailReturnsActualCountAndFalseWithoutMyReactionQuery() {
        given(reactions.countForPost(101L, "LIKE")).willReturn(3L);
        given(activePets.findActivePet(1L)).willReturn(Optional.empty());

        var result = service().findForPost(1L, 101L);

        assertThat(result.reactionCount()).isEqualTo(3);
        assertThat(result.reactedByMe()).isFalse();
        then(reactions).should().countForPost(101L, "LIKE");
        then(reactions).shouldHaveNoMoreInteractions();
    }

    private BoardPostReactionRepository.PostReactionCount count(long postId, long reactionCount) {
        return new BoardPostReactionRepository.PostReactionCount() {
            @Override public Long getPostId() { return postId; }
            @Override public long getReactionCount() { return reactionCount; }
        };
    }

    private BoardPostReactionQueryService service() {
        return new BoardPostReactionQueryService(reactions, activePets);
    }
}
