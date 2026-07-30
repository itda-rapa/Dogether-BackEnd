package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import itda.block.service.BlockRelationshipQueryService;
import itda.chat.domain.RoomOrigin;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.domain.FriendRequest;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.domain.Friendship;
import itda.friend.repository.FriendRequestRepository;
import itda.friend.repository.FriendshipRepository;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.user.domain.AccountStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
    private ChatRoomService chatRoomService;
    @Mock
    private PetDisplayQueryService petDisplayQueryService;

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
                chatRoomService,
                petDisplayQueryService,
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
        lenient().when(petDisplayQueryService.getPetDisplaySummaries(any()))
                .thenReturn(displaySummaries());
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
        verify(chatRoomService, never())
                .ensureDirectRoom(any(Long.class), any(Long.class), any());
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

        verify(chatRoomService, never())
                .ensureDirectRoom(any(Long.class), any(Long.class), any());
        verify(petDisplayQueryService, never())
                .getPetDisplaySummaries(any());
    }

    @Test
    void autoAcceptsReversePendingAndFlushesBeforeChat() {
        FriendRequest reverse = pending(TARGET_PET_ID, SOURCE_PET_ID);
        given(friendRequestRepository.findPendingPairForUpdate(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(Optional.of(reverse));
        given(clock.instant()).willReturn(NOW);
        given(friendshipRepository.countRelationshipsByPetIds(any()))
                .willReturn(List.of());
        given(friendshipRepository.save(any(Friendship.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(chatRoomService.ensureDirectRoom(
                SOURCE_PET_ID,
                TARGET_PET_ID,
                RoomOrigin.FRIEND
        )).willReturn(new EnsureDirectRoomResult(99L, true));

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
        InOrder order = inOrder(friendshipRepository, chatRoomService);
        order.verify(friendshipRepository).save(any(Friendship.class));
        order.verify(friendshipRepository).flush();
        order.verify(chatRoomService).ensureDirectRoom(
                SOURCE_PET_ID,
                TARGET_PET_ID,
                RoomOrigin.FRIEND
        );
    }

    @Test
    void leavesReversePendingUntouchedAtFriendLimit() {
        FriendRequest reverse = pending(TARGET_PET_ID, SOURCE_PET_ID);
        given(friendRequestRepository.findPendingPairForUpdate(
                SOURCE_PET_ID,
                TARGET_PET_ID
        )).willReturn(Optional.of(reverse));
        given(clock.instant()).willReturn(NOW);
        FriendshipRepository.FriendshipCountRow count =
                mock(FriendshipRepository.FriendshipCountRow.class);
        given(count.getPetId()).willReturn(SOURCE_PET_ID);
        given(count.getFriendCount()).willReturn(50L);
        given(friendshipRepository.countRelationshipsByPetIds(any()))
                .willReturn(List.of(count));

        assertError(
                () -> service.execute(USER_ID, TARGET_PET_ID),
                ErrorCode.FRIEND_LIMIT_EXCEEDED
        );

        assertThat(reverse.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        verify(friendshipRepository, never()).save(any(Friendship.class));
        verify(chatRoomService, never())
                .ensureDirectRoom(any(Long.class), any(Long.class), any());
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
        verify(chatRoomService, never())
                .ensureDirectRoom(any(Long.class), any(Long.class), any());
        verify(petDisplayQueryService, never())
                .getPetDisplaySummaries(any());
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

    private Map<Long, PetDisplaySummary> displaySummaries() {
        return Map.of(
                SOURCE_PET_ID,
                new PetDisplaySummary(
                        SOURCE_PET_ID,
                        USER_ID,
                        "source#tag",
                        "source",
                        null,
                        true,
                        PetStatus.ACTIVE,
                        null
                ),
                TARGET_PET_ID,
                new PetDisplaySummary(
                        TARGET_PET_ID,
                        TARGET_USER_ID,
                        "target#tag",
                        "target",
                        null,
                        true,
                        PetStatus.ACTIVE,
                        null
                )
        );
    }
}
