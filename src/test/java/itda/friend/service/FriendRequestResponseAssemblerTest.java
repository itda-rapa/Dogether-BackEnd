package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import itda.friend.domain.FriendRelationship;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.service.FriendRequestResponseAssembler.Snapshot;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FriendRequestResponseAssemblerTest {

    private static final Long REQUESTER_PET_ID = 10L;
    private static final Long TARGET_PET_ID = 20L;
    private static final Instant REQUESTED_AT =
            Instant.parse("2026-07-30T05:00:00Z");
    private static final Instant RESPONDED_AT =
            Instant.parse("2026-07-30T05:10:00Z");
    private static final Instant EXPIRES_AT =
            Instant.parse("2026-08-06T05:00:00Z");

    @Mock
    private PetDisplayQueryService petDisplayQueryService;

    private FriendRequestResponseAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new FriendRequestResponseAssembler(
                petDisplayQueryService
        );
        given(petDisplayQueryService.getPetDisplaySummaries(
                org.mockito.ArgumentMatchers.any()
        )).willReturn(displaySummaries());
    }

    @Test
    void assemblesCreatedPendingInStoredDirection() {
        FriendRequestResponse response = assembler.created(
                snapshot(FriendRequestStatus.PENDING, null),
                REQUESTER_PET_ID
        );

        assertDirectionAndSingleDisplayLookup(response);
        assertThat(response.status()).isEqualTo(FriendRequestStatus.PENDING);
        assertThat(response.requesterPet().relationship())
                .isEqualTo(FriendRelationship.NONE);
        assertThat(response.targetPet().relationship())
                .isEqualTo(FriendRelationship.REQUEST_SENT);
        assertThat(response.directRoomId()).isNull();
    }

    @Test
    void assemblesAutoAcceptForExistingReverseRequestDirection() {
        FriendRequestResponse response = assembler.accepted(
                snapshot(FriendRequestStatus.ACCEPTED, RESPONDED_AT),
                TARGET_PET_ID,
                99L
        );

        assertDirectionAndSingleDisplayLookup(response);
        assertThat(response.status()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(response.requesterPet().relationship())
                .isEqualTo(FriendRelationship.FRIEND);
        assertThat(response.targetPet().relationship())
                .isEqualTo(FriendRelationship.NONE);
        assertThat(response.directRoomId()).isEqualTo(99L);
    }

    @Test
    void assemblesExplicitAcceptForReceiverWithoutReversingRequest() {
        FriendRequestResponse response = assembler.accepted(
                snapshot(FriendRequestStatus.ACCEPTED, RESPONDED_AT),
                TARGET_PET_ID,
                100L
        );

        assertDirectionAndSingleDisplayLookup(response);
        assertThat(response.status()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(response.requesterPet().relationship())
                .isEqualTo(FriendRelationship.FRIEND);
        assertThat(response.targetPet().relationship())
                .isEqualTo(FriendRelationship.NONE);
        assertThat(response.directRoomId()).isEqualTo(100L);
    }

    @Test
    void assemblesRejectedWithoutRelationshipsOrRoom() {
        FriendRequestResponse response = assembler.rejected(
                snapshot(FriendRequestStatus.REJECTED, RESPONDED_AT)
        );

        assertDirectionAndSingleDisplayLookup(response);
        assertThat(response.status()).isEqualTo(FriendRequestStatus.REJECTED);
        assertThat(response.requesterPet().relationship())
                .isEqualTo(FriendRelationship.NONE);
        assertThat(response.targetPet().relationship())
                .isEqualTo(FriendRelationship.NONE);
        assertThat(response.directRoomId()).isNull();
    }

    private void assertDirectionAndSingleDisplayLookup(
            FriendRequestResponse response
    ) {
        assertThat(response.requesterPet().petId())
                .isEqualTo(REQUESTER_PET_ID);
        assertThat(response.targetPet().petId()).isEqualTo(TARGET_PET_ID);
        ArgumentCaptor<Collection<Long>> ids =
                ArgumentCaptor.captor();
        verify(petDisplayQueryService).getPetDisplaySummaries(ids.capture());
        assertThat(ids.getValue())
                .containsExactly(REQUESTER_PET_ID, TARGET_PET_ID);
        verifyNoMoreInteractions(petDisplayQueryService);
    }

    private Snapshot snapshot(
            FriendRequestStatus status,
            Instant respondedAt
    ) {
        return new Snapshot(
                1L,
                REQUESTER_PET_ID,
                TARGET_PET_ID,
                status,
                REQUESTED_AT,
                respondedAt,
                EXPIRES_AT
        );
    }

    private Map<Long, PetDisplaySummary> displaySummaries() {
        return Map.of(
                REQUESTER_PET_ID,
                display(REQUESTER_PET_ID, 1L, "requester"),
                TARGET_PET_ID,
                display(TARGET_PET_ID, 2L, "target")
        );
    }

    private PetDisplaySummary display(
            Long petId,
            Long userId,
            String nickname
    ) {
        return new PetDisplaySummary(
                petId,
                userId,
                nickname + "#TAG1",
                nickname,
                null,
                true,
                PetStatus.ACTIVE,
                null
        );
    }
}
