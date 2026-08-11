package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.block.service.BlockRelationshipQueryService;
import itda.board.repository.BoardRepository;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.service.BoardPostService;
import itda.boardpost.service.LockedActivePetCommandGuard;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BoardPostServiceTest {

    @Mock private BoardPostRepository posts;
    @Mock private BoardRepository boards;
    @Mock private UserRepository users;
    @Mock private LockedActivePetCommandGuard actorGuard;
    @Mock private PetDisplayQueryService petDisplays;
    @Mock private BlockRelationshipQueryService blocks;

    @Test
    void feedDeduplicatesAuthorPetIdsAndUsesOneBatchDisplayQuery() {
        BoardPostService service = new BoardPostService(posts, boards, users, actorGuard, petDisplays, blocks);
        User viewer = User.register("viewer@test.com", "encoded", "viewer", "viewer#A1B2C3D4", "4113111500");
        ReflectionTestUtils.setField(viewer, "id", 1L);
        given(users.findById(1L)).willReturn(Optional.of(viewer));
        given(boards.existsById(10L)).willReturn(true);
        BoardPost first = post(101L, 20L, 200L);
        BoardPost second = post(102L, 20L, 200L);
        BoardPost third = post(103L, 21L, 201L);
        given(posts.findVisibleFeed(10L, "4113111500", 1L, null, null, 21))
                .willReturn(List.of(first, second, third));
        given(petDisplays.getPetDisplaySummaries(org.mockito.ArgumentMatchers.anyCollection()))
                .willReturn(Map.of(200L, summary(200L), 201L, summary(201L)));

        assertThat(service.feed(1L, 10L, null, null).items()).hasSize(3);
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        then(petDisplays).should().getPetDisplaySummaries(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(200L, 201L);
        then(petDisplays).shouldHaveNoMoreInteractions();
    }

    private BoardPost post(long id, long authorUserId, long petId) {
        BoardPost post = BoardPost.publish(10L, authorUserId, petId, "4113111500", "title", "content");
        ReflectionTestUtils.setField(post, "id", id);
        ReflectionTestUtils.setField(post, "createdAt", Instant.parse("2026-08-10T00:00:00Z"));
        ReflectionTestUtils.setField(post, "updatedAt", Instant.parse("2026-08-10T00:00:00Z"));
        return post;
    }

    private PetDisplaySummary summary(long id) {
        return new PetDisplaySummary(id, 1L, "pet#A1B2", "pet", null, false, PetStatus.ACTIVE, null);
    }
}
