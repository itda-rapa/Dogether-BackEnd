package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.domain.FriendRequest;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.domain.Friendship;
import itda.friend.dto.response.FriendRequestPetResponse;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.repository.FriendRequestRepository;
import itda.friend.repository.FriendshipRepository;
import itda.friend.service.FriendRequestResponseAssembler.Snapshot;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FriendRequestCommandTransactionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;
    private static final Long SOURCE_PET_ID = 10L;
    private static final Long TARGET_PET_ID = 20L;
    private static final Instant NOW =
            Instant.parse("2026-07-30T05:00:00Z");

    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private InteractionPairLockService interactionPairLockService;
    @Mock
    private BlockRelationshipQueryService blockRelationshipQueryService;
    @Mock
    private FriendRequestRepository friendRequestRepository;
    @Mock
    private FriendshipRepository friendshipRepository;
    @Mock
    private FriendRequestAcceptanceService acceptanceService;
    @Mock
    private FriendRequestResponseAssembler responseAssembler;

    private Clock clock;
    private FriendRequestCommandTransactionService service;

    @BeforeEach
    void setUp() {
        clock = mock(Clock.class);
        service = new FriendRequestCommandTransactionService(
                activePetQueryService,
                interactionPairLockService,
                blockRelationshipQueryService,
                friendRequestRepository,
                friendshipRepository,
                acceptanceService,
                responseAssembler,
                clock
        );
        given(activePetQueryService.requireActivePet(USER_ID))
                .willReturn(activePet());
        lenient().when(interactionPairLockService.lockInteractionPair(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).thenReturn(pair());
        lenient().when(friendshipRepository.existsByPetLowIdAndPetHighId(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).thenReturn(false);
        lenient().when(responseAssembler.created(any(), any()))
                .thenAnswer(invocation -> response(
                        invocation.getArgument(0),
                        null,
                        FriendRelationship.REQUEST_SENT
                ));
        lenient().when(acceptanceService.accept(any(), any(), any()))
                .thenAnswer(invocation -> {
                    FriendRequest pending = invocation.getArgument(0);
                    pending.accept(invocation.getArgument(2));
                    return response(
                            Snapshot.from(pending),
                            99L,
                            FriendRelationship.FRIEND
                    );
                });
    }

    @Test
    void createsPendingRequestWithOneClockRead() {
        given(friendRequestRepository.findPendingPairForUpdate(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(Optional.empty());
        given(clock.instant()).willReturn(NOW);
        given(friendRequestRepository.saveAndFlush(any(FriendRequest.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        FriendRequestCommandResult result =
                service.execute(USER_ID, TARGET_PET_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.response().status())
                .isEqualTo(FriendRequestStatus.PENDING);
        assertThat(result.response().requestedAt()).isEqualTo(NOW);
        assertThat(result.response().expiresAt())
                .isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60));
        assertThat(result.response().respondedAt()).isNull();
        assertThat(result.response().directRoomId()).isNull();
        assertThat(result.response().requesterPet().relationship())
                .isEqualTo(FriendRelationship.NONE);
        assertThat(result.response().targetPet().relationship())
                .isEqualTo(FriendRelationship.REQUEST_SENT);
        verify(clock).instant();
        verify(friendshipRepository, never())
                .countRelationshipsByPetIds(any());
        verify(acceptanceService, never()).accept(any(), any(), any());
    }

    @Test
    void expiresLockedRequestBeforeCreatingReplacement() {
        FriendRequest expired = FriendRequest.createPending(
                SOURCE_PET_ID,
                TARGET_PET_ID,
                NOW.minusSeconds(8 * 24 * 60 * 60),
                NOW.minusSeconds(24 * 60 * 60)
        );
        given(friendRequestRepository.findPendingPairForUpdate(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(Optional.of(expired));
        given(clock.instant()).willReturn(NOW);
        given(friendRequestRepository.saveAndFlush(any(FriendRequest.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        FriendRequestCommandResult result =
                service.execute(USER_ID, TARGET_PET_ID);

        assertThat(expired.getStatus())
                .isEqualTo(FriendRequestStatus.EXPIRED);
        assertThat(expired.getRespondedAt()).isNull();
        assertThat(result.created()).isTrue();
        InOrder order = inOrder(friendRequestRepository);
        order.verify(friendRequestRepository).flush();
        order.verify(friendRequestRepository)
                .saveAndFlush(any(FriendRequest.class));
    }

    @Test
    void rejectsActivePendingRequestInSameDirection() {
        FriendRequest pending = pending(SOURCE_PET_ID, TARGET_PET_ID);
        given(friendRequestRepository.findPendingPairForUpdate(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(Optional.of(pending));
        given(clock.instant()).willReturn(NOW);

        assertError(
                () -> service.execute(USER_ID, TARGET_PET_ID),
                ErrorCode.FRIEND_REQUEST_ALREADY_PENDING
        );

        verify(acceptanceService, never()).accept(any(), any(), any());
        verify(responseAssembler, never()).created(any(), any());
    }

    @Test
    void delegatesReversePendingToSharedAcceptanceService() {
        FriendRequest reverse = pending(TARGET_PET_ID, SOURCE_PET_ID);
        given(friendRequestRepository.findPendingPairForUpdate(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(Optional.of(reverse));
        given(clock.instant()).willReturn(NOW);
        FriendRequestCommandResult result =
                service.execute(USER_ID, TARGET_PET_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.response().status())
                .isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(result.response().respondedAt()).isEqualTo(NOW);
        assertThat(result.response().directRoomId()).isEqualTo(99L);
        assertThat(result.response().requesterPet().petId())
                .isEqualTo(TARGET_PET_ID);
        assertThat(result.response().requesterPet().relationship())
                .isEqualTo(FriendRelationship.FRIEND);
        assertThat(result.response().targetPet().petId())
                .isEqualTo(SOURCE_PET_ID);
        assertThat(result.response().targetPet().relationship())
                .isEqualTo(FriendRelationship.NONE);
        verify(acceptanceService).accept(
                reverse,
                SOURCE_PET_ID,
                NOW
        );
    }

    @Test
    void propagatesFriendLimitFromSharedAcceptanceService() {
        FriendRequest reverse = pending(TARGET_PET_ID, SOURCE_PET_ID);
        given(friendRequestRepository.findPendingPairForUpdate(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(Optional.of(reverse));
        given(clock.instant()).willReturn(NOW);
        doThrow(new BusinessException(ErrorCode.FRIEND_LIMIT_EXCEEDED))
                .when(acceptanceService)
                .accept(reverse, SOURCE_PET_ID, NOW);

        assertError(
                () -> service.execute(USER_ID, TARGET_PET_ID),
                ErrorCode.FRIEND_LIMIT_EXCEEDED
        );

        assertThat(reverse.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        verify(friendshipRepository, never()).save(any(Friendship.class));
    }

    @Test
    void rejectsBlockBeforeRelationshipQueries() {
        given(blockRelationshipQueryService.existsBlockBetween(
                USER_ID,
                TARGET_USER_ID
        )).willReturn(true);

        assertError(
                () -> service.execute(USER_ID, TARGET_PET_ID),
                ErrorCode.BLOCKED_USER
        );

        verify(friendshipRepository, never())
                .existsByPetLowIdAndPetHighId(any(), any());
        verify(friendRequestRepository, never())
                .findPendingPairForUpdate(any(), any());
        verify(clock, never()).instant();
    }

    @Test
    void mapsTargetStateWithoutExposingAccountStatus() {
        InteractionPairContext suspendedTarget = new InteractionPairContext(
                sourceUser(),
                new LockedUserContext(
                        TARGET_USER_ID,
                        AccountStatus.SUSPENDED,
                        TARGET_PET_ID,
                        "target#tag"
                ),
                sourcePet(),
                targetPet(PetStatus.ACTIVE, null)
        );
        given(interactionPairLockService.lockInteractionPair(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(suspendedTarget);

        assertError(
                () -> service.execute(USER_ID, TARGET_PET_ID),
                ErrorCode.PET_NOT_ACTIVE
        );
        verify(blockRelationshipQueryService, never())
                .existsBlockBetween(any(), any());
    }

    @Test
    void hidesSoftDeletedTargetAsNotFound() {
        InteractionPairContext deletedTarget = new InteractionPairContext(
                sourceUser(),
                targetUser(),
                sourcePet(),
                targetPet(PetStatus.DELETED, NOW)
        );
        given(interactionPairLockService.lockInteractionPair(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(deletedTarget);

        assertError(
                () -> service.execute(USER_ID, TARGET_PET_ID),
                ErrorCode.PET_NOT_FOUND
        );
    }

    @Test
    void rejectsSuspendedTargetPetAsNotActive() {
        InteractionPairContext suspendedTarget = new InteractionPairContext(
                sourceUser(),
                targetUser(),
                sourcePet(),
                targetPet(PetStatus.SUSPENDED, null)
        );
        given(interactionPairLockService.lockInteractionPair(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(suspendedTarget);

        assertError(
                () -> service.execute(USER_ID, TARGET_PET_ID),
                ErrorCode.PET_NOT_ACTIVE
        );
    }

    @Test
    void rejectsChangedSourceActivePet() {
        InteractionPairContext changedSource = new InteractionPairContext(
                new LockedUserContext(
                        USER_ID,
                        AccountStatus.ACTIVE,
                        999L,
                        "source#tag"
                ),
                targetUser(),
                sourcePet(),
                targetPet(PetStatus.ACTIVE, null)
        );
        given(interactionPairLockService.lockInteractionPair(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(changedSource);

        assertError(
                () -> service.execute(USER_ID, TARGET_PET_ID),
                ErrorCode.ACTIVE_PET_REQUIRED
        );
    }

    @Test
    void rejectsDifferentPetOwnedBySameUser() {
        InteractionPairContext sameOwner = new InteractionPairContext(
                sourceUser(),
                sourceUser(),
                sourcePet(),
                new LockedPetContext(
                        TARGET_PET_ID,
                        USER_ID,
                        PetStatus.ACTIVE,
                        null
                )
        );
        given(interactionPairLockService.lockInteractionPair(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(sameOwner);

        assertError(
                () -> service.execute(USER_ID, TARGET_PET_ID),
                ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN
        );
        verify(blockRelationshipQueryService, never())
                .existsBlockBetween(any(), any());
    }

    @Test
    void rejectsRequestToSamePetWithoutCallingDownstreamDependencies() {
        InteractionPairContext samePet = new InteractionPairContext(
                sourceUser(),
                sourceUser(),
                sourcePet(),
                sourcePet()
        );
        given(interactionPairLockService.lockInteractionPair(
                SOURCE_PET_ID,
                SOURCE_PET_ID
        )).willReturn(samePet);

        assertError(
                () -> service.execute(USER_ID, SOURCE_PET_ID),
                ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN
        );

        verify(interactionPairLockService).lockInteractionPair(
                SOURCE_PET_ID,
                SOURCE_PET_ID
        );
        verify(blockRelationshipQueryService, never())
                .existsBlockBetween(any(), any());
        verify(friendshipRepository, never())
                .existsByPetLowIdAndPetHighId(any(), any());
        verify(friendRequestRepository, never())
                .findPendingPairForUpdate(any(), any());
        verify(clock, never()).instant();
        verify(acceptanceService, never()).accept(any(), any(), any());
        verify(responseAssembler, never()).created(any(), any());
        verify(friendRequestRepository, never())
                .save(any(FriendRequest.class));
        verify(friendRequestRepository, never())
                .saveAndFlush(any(FriendRequest.class));
        verify(friendRequestRepository, never()).flush();
        verify(friendshipRepository, never())
                .save(any(Friendship.class));
        verify(friendshipRepository, never()).flush();
    }

    @Test
    void rejectsExistingFriendshipBeforeReadingPendingRequest() {
        given(friendshipRepository.existsByPetLowIdAndPetHighId(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(true);

        assertError(
                () -> service.execute(USER_ID, TARGET_PET_ID),
                ErrorCode.FRIENDSHIP_ALREADY_EXISTS
        );

        verify(friendRequestRepository, never())
                .findPendingPairForUpdate(any(), any());
        verify(clock, never()).instant();
    }

    private void assertError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private FriendRequest pending(Long requesterPetId, Long targetPetId) {
        return FriendRequest.createPending(
                requesterPetId,
                targetPetId,
                NOW.minusSeconds(60),
                NOW.plusSeconds(60)
        );
    }

    private ActivePetContext activePet() {
        return new ActivePetContext(
                SOURCE_PET_ID,
                USER_ID,
                "source#tag",
                "source",
                null,
                true
        );
    }

    private InteractionPairContext pair() {
        return new InteractionPairContext(
                sourceUser(),
                targetUser(),
                sourcePet(),
                targetPet(PetStatus.ACTIVE, null)
        );
    }

    private LockedUserContext sourceUser() {
        return new LockedUserContext(
                USER_ID,
                AccountStatus.ACTIVE,
                SOURCE_PET_ID,
                "source#tag"
        );
    }

    private LockedUserContext targetUser() {
        return new LockedUserContext(
                TARGET_USER_ID,
                AccountStatus.ACTIVE,
                TARGET_PET_ID,
                "target#tag"
        );
    }

    private LockedPetContext sourcePet() {
        return new LockedPetContext(
                SOURCE_PET_ID,
                USER_ID,
                PetStatus.ACTIVE,
                null
        );
    }

    private LockedPetContext targetPet(
            PetStatus status,
            Instant deletedAt
    ) {
        return new LockedPetContext(
                TARGET_PET_ID,
                TARGET_USER_ID,
                status,
                deletedAt
        );
    }

    private FriendRequestResponse response(
            Snapshot snapshot,
            Long roomId,
            FriendRelationship counterpartRelationship
    ) {
        Long actorPetId = SOURCE_PET_ID;
        return new FriendRequestResponse(
                snapshot.requestId(),
                new FriendRequestPetResponse(
                        snapshot.requesterPetId(),
                        "requester#tag",
                        "requester",
                        null,
                        true,
                        snapshot.requesterPetId().equals(actorPetId)
                                ? FriendRelationship.NONE
                                : counterpartRelationship
                ),
                new FriendRequestPetResponse(
                        snapshot.targetPetId(),
                        "target#tag",
                        "target",
                        null,
                        true,
                        snapshot.targetPetId().equals(actorPetId)
                                ? FriendRelationship.NONE
                                : counterpartRelationship
                ),
                snapshot.status(),
                snapshot.requestedAt(),
                snapshot.respondedAt(),
                snapshot.expiresAt(),
                roomId
        );
    }
}
