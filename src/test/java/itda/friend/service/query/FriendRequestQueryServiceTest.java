package itda.friend.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.dto.response.FriendRequestListResponse;
import itda.friend.repository.FriendRequestRepository;
import itda.friend.repository.FriendRequestRepository.FriendRequestListRow;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FriendRequestQueryServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ACTIVE_PET_ID = 10L;
    private static final Long COUNTERPART_PET_ID = 20L;
    private static final Instant NOW =
            Instant.parse("2026-07-30T10:00:00Z");

    @Mock
    private FriendRequestRepository friendRequestRepository;

    @Mock
    private ActivePetQueryService activePetQueryService;

    @Mock
    private PetDisplayQueryService petDisplayQueryService;

    @Mock
    private Clock clock;

    private FriendRequestQueryService service;

    @BeforeEach
    void setUp() {
        service = new FriendRequestQueryService(
                friendRequestRepository,
                activePetQueryService,
                petDisplayQueryService,
                clock
        );
        given(activePetQueryService.requireActivePet(USER_ID))
                .willReturn(activePet());
    }

    @Test
    void listsReceivedRequestsWithOnePetDisplayBatch() {
        FriendRequestListRow row = row(
                1L,
                COUNTERPART_PET_ID,
                ACTIVE_PET_ID,
                NOW.minusSeconds(60)
        );
        given(clock.instant()).willReturn(NOW);
        given(friendRequestRepository.findReceivedPendingPage(
                ACTIVE_PET_ID, NOW, null, null, 21
        )).willReturn(List.of(row));
        given(petDisplayQueryService.getPetDisplaySummaries(
                Set.of(COUNTERPART_PET_ID, ACTIVE_PET_ID)
        )).willReturn(displays());

        FriendRequestListResponse response =
                service.listReceived(USER_ID, null, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).requestId()).isEqualTo(1L);
        assertThat(response.items().get(0).requesterPet().relationship())
                .isEqualTo(FriendRelationship.REQUEST_RECEIVED);
        assertThat(response.items().get(0).targetPet().relationship())
                .isEqualTo(FriendRelationship.NONE);
        assertThat(response.items().get(0).respondedAt()).isNull();
        assertThat(response.items().get(0).directRoomId()).isNull();
        assertThat(response.page().hasNext()).isFalse();
        verify(clock).instant();
        verify(petDisplayQueryService).getPetDisplaySummaries(
                Set.of(COUNTERPART_PET_ID, ACTIVE_PET_ID)
        );
    }

    @Test
    void listsSentRequestsAndBuildsNextCursorFromLastReturnedRow() {
        FriendRequestListRow first = row(
                3L,
                ACTIVE_PET_ID,
                COUNTERPART_PET_ID,
                NOW.minusSeconds(30)
        );
        FriendRequestListRow second = row(
                2L,
                ACTIVE_PET_ID,
                30L,
                NOW.minusSeconds(60)
        );
        given(clock.instant()).willReturn(NOW);
        given(friendRequestRepository.findSentPendingPage(
                ACTIVE_PET_ID, NOW, null, null, 2
        )).willReturn(List.of(first, second));
        given(petDisplayQueryService.getPetDisplaySummaries(
                Set.of(ACTIVE_PET_ID, COUNTERPART_PET_ID)
        )).willReturn(displays());

        FriendRequestListResponse response =
                service.listSent(USER_ID, null, 1);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).requesterPet().relationship())
                .isEqualTo(FriendRelationship.NONE);
        assertThat(response.items().get(0).targetPet().relationship())
                .isEqualTo(FriendRelationship.REQUEST_SENT);
        assertThat(response.page().hasNext()).isTrue();
        assertThat(response.page().nextCursor()).isNotBlank();
        verify(petDisplayQueryService).getPetDisplaySummaries(
                Set.of(ACTIVE_PET_ID, COUNTERPART_PET_ID)
        );
    }

    @Test
    void emptyPageSkipsPetDisplayLookup() {
        given(clock.instant()).willReturn(NOW);
        given(friendRequestRepository.findReceivedPendingPage(
                ACTIVE_PET_ID, NOW, null, null, 21
        )).willReturn(List.of());

        FriendRequestListResponse response =
                service.listReceived(USER_ID, null, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.page().nextCursor()).isNull();
        verifyNoInteractions(petDisplayQueryService);
    }

    @Test
    void invalidLimitFailsBeforeClockAndRepository() {
        assertThatThrownBy(() -> service.listReceived(USER_ID, null, 101))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(clock, never()).instant();
        verifyNoInteractions(friendRequestRepository);
        verifyNoInteractions(petDisplayQueryService);
    }

    private ActivePetContext activePet() {
        return new ActivePetContext(
                ACTIVE_PET_ID,
                USER_ID,
                "몽이#A1B2",
                "몽이",
                null,
                false
        );
    }

    private FriendRequestListRow row(
            Long requestId,
            Long requesterPetId,
            Long targetPetId,
            Instant requestedAt
    ) {
        return new FriendRequestListRow() {
            @Override
            public Long getRequestId() {
                return requestId;
            }

            @Override
            public Long getRequesterPetId() {
                return requesterPetId;
            }

            @Override
            public Long getTargetPetId() {
                return targetPetId;
            }

            @Override
            public String getStatus() {
                return "PENDING";
            }

            @Override
            public Instant getRequestedAt() {
                return requestedAt;
            }

            @Override
            public Instant getRespondedAt() {
                return null;
            }

            @Override
            public Instant getExpiresAt() {
                return requestedAt.plusSeconds(7 * 24 * 60 * 60);
            }
        };
    }

    private Map<Long, PetDisplaySummary> displays() {
        return Map.of(
                ACTIVE_PET_ID,
                display(ACTIVE_PET_ID, USER_ID, "몽이"),
                COUNTERPART_PET_ID,
                display(COUNTERPART_PET_ID, 2L, "콩이")
        );
    }

    private PetDisplaySummary display(
            Long petId,
            Long ownerId,
            String nickname
    ) {
        return new PetDisplaySummary(
                petId,
                ownerId,
                nickname + "#A1B2",
                nickname,
                null,
                false,
                PetStatus.ACTIVE,
                null
        );
    }
}
