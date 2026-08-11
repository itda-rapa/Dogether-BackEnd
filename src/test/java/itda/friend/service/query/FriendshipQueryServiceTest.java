package itda.friend.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.dto.response.PetFriendListResponse;
import itda.friend.repository.FriendshipRepository;
import itda.friend.repository.FriendshipRepository.FriendshipListRow;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.service.MyPetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
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
class FriendshipQueryServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 10L;
    private static final Long COUNTERPART_ID = 20L;
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-30T10:00:00Z");

    @Mock
    private FriendshipRepository friendshipRepository;

    @Mock
    private MyPetQueryService myPetQueryService;

    @Mock
    private PetDisplayQueryService petDisplayQueryService;

    @Mock
    private PetResponse ownedPet;

    private FriendshipQueryService service;

    @BeforeEach
    void setUp() {
        service = new FriendshipQueryService(
                friendshipRepository,
                myPetQueryService,
                petDisplayQueryService
        );
        given(myPetQueryService.getMyPet(USER_ID, PET_ID))
                .willReturn(ownedPet);
    }

    @Test
    void listsCounterpartPetsWithFriendRelationship() {
        FriendshipListRow row = row(3L, COUNTERPART_ID, CREATED_AT);
        given(friendshipRepository.findFriendPage(
                PET_ID, null, null, 21
        )).willReturn(List.of(row));
        given(petDisplayQueryService.getPetDisplaySummaries(
                Set.of(COUNTERPART_ID)
        )).willReturn(Map.of(COUNTERPART_ID, display()));

        PetFriendListResponse response =
                service.listFriends(USER_ID, PET_ID, null, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).petId())
                .isEqualTo(COUNTERPART_ID);
        assertThat(response.items().get(0).relationship())
                .isEqualTo(FriendRelationship.FRIEND);
        assertThat(response.page().hasNext()).isFalse();
        verify(myPetQueryService).getMyPet(USER_ID, PET_ID);
        verify(petDisplayQueryService).getPetDisplaySummaries(
                Set.of(COUNTERPART_ID)
        );
    }

    @Test
    void ownedSuspendedOrNonActivePetUsesSameReadContract() {
        given(friendshipRepository.findFriendPage(
                PET_ID, null, null, 21
        )).willReturn(List.of());

        PetFriendListResponse response =
                service.listFriends(USER_ID, PET_ID, null, null);

        assertThat(response.items()).isEmpty();
        verify(myPetQueryService).getMyPet(USER_ID, PET_ID);
        verifyNoInteractions(petDisplayQueryService);
    }

    @Test
    void ownershipFailureIsPreserved() {
        given(myPetQueryService.getMyPet(USER_ID, PET_ID))
                .willThrow(new BusinessException(ErrorCode.PET_NOT_OWNED));

        assertThatThrownBy(() ->
                service.listFriends(USER_ID, PET_ID, null, null)
        ).isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.PET_NOT_OWNED);

        verifyNoInteractions(friendshipRepository);
        verifyNoInteractions(petDisplayQueryService);
    }

    @Test
    void invalidLimitFailsBeforeRepositoryPageQuery() {
        assertThatThrownBy(() ->
                service.listFriends(USER_ID, PET_ID, null, 0)
        ).isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(friendshipRepository);
        verifyNoInteractions(petDisplayQueryService);
    }

    private FriendshipListRow row(
            Long friendshipId,
            Long counterpartId,
            Instant createdAt
    ) {
        return new FriendshipListRow() {
            @Override
            public Long getFriendshipId() {
                return friendshipId;
            }

            @Override
            public Instant getCreatedAt() {
                return createdAt;
            }

            @Override
            public Long getCounterpartPetId() {
                return counterpartId;
            }
        };
    }

    private PetDisplaySummary display() {
        return new PetDisplaySummary(
                COUNTERPART_ID,
                2L,
                "콩이#A1B2",
                "콩이",
                null,
                false,
                PetStatus.SUSPENDED,
                null
        );
    }
}
