package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRequest;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.repository.FriendRequestRepository;
import itda.friend.repository.FriendRequestRepository.FriendRequestPairRow;
import itda.friend.repository.FriendshipRepository;
import itda.friend.service.FriendRequestActionResult.Accepted;
import itda.friend.service.FriendRequestActionResult.Rejected;
import itda.friend.service.FriendRequestActionResult.Terminal;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FriendRequestActionTransactionServiceTest {

    private static final Long REQUESTER_USER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;
    private static final Long REQUESTER_PET_ID = 10L;
    private static final Long TARGET_PET_ID = 20L;
    private static final Long REQUEST_ID = 30L;
    private static final Instant NOW =
            Instant.parse("2026-07-30T05:00:00Z");

    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private FriendRequestRepository friendRequestRepository;
    @Mock
    private InteractionPairLockService interactionPairLockService;
    @Mock
    private BlockRelationshipQueryService blockRelationshipQueryService;
    @Mock
    private FriendshipRepository friendshipRepository;
    @Mock
    private FriendRequestAcceptanceService acceptanceService;
    @Mock
    private FriendRequestResponseAssembler responseAssembler;
    @Mock
    private Clock clock;

    private FriendRequestActionTransactionService service;

    @BeforeEach
    void setUp() {
        service = new FriendRequestActionTransactionService(
                activePetQueryService,
                friendRequestRepository,
                interactionPairLockService,
                blockRelationshipQueryService,
                friendshipRepository,
                acceptanceService,
                responseAssembler,
                clock
        );
    }

    @Test
    void acceptsAsTargetUsingGlobalLockOrder() {
        FriendRequest pending = prepareTarget(pending());
        FriendRequestResponse response = org.mockito.Mockito.mock(
                FriendRequestResponse.class
        );
        given(clock.instant()).willReturn(NOW);
        given(acceptanceService.accept(pending, TARGET_PET_ID, NOW))
                .willReturn(response);

        FriendRequestActionResult result =
                service.accept(TARGET_USER_ID, REQUEST_ID);

        assertThat(result).isEqualTo(new Accepted(response));
        InOrder order = inOrder(
                activePetQueryService,
                friendRequestRepository,
                interactionPairLockService,
                blockRelationshipQueryService,
                friendshipRepository,
                acceptanceService
        );
        order.verify(activePetQueryService)
                .requireActivePet(TARGET_USER_ID);
        order.verify(friendRequestRepository).findPairById(REQUEST_ID);
        order.verify(interactionPairLockService).lockInteractionPair(
                REQUESTER_PET_ID,
                TARGET_PET_ID
        );
        order.verify(friendRequestRepository).findByIdForUpdate(REQUEST_ID);
        order.verify(blockRelationshipQueryService).existsBlockBetween(
                REQUESTER_USER_ID,
                TARGET_USER_ID
        );
        order.verify(friendshipRepository)
                .existsByPetLowIdAndPetHighId(
                        REQUESTER_PET_ID,
                        TARGET_PET_ID
                );
        order.verify(acceptanceService).accept(
                pending,
                TARGET_PET_ID,
                NOW
        );
        verify(clock).instant();
    }

    @Test
    void rejectsAsTargetWithoutRelationshipCreatingDependencies() {
        FriendRequest pending = prepareTarget(pending());
        FriendRequestResponse response = org.mockito.Mockito.mock(
                FriendRequestResponse.class
        );
        given(clock.instant()).willReturn(NOW);
        given(responseAssembler.rejected(any())).willReturn(response);

        FriendRequestActionResult result =
                service.reject(TARGET_USER_ID, REQUEST_ID);

        assertThat(result).isEqualTo(new Rejected(response));
        assertThat(pending.getStatus()).isEqualTo(FriendRequestStatus.REJECTED);
        assertThat(pending.getRespondedAt()).isEqualTo(NOW);
        verify(friendRequestRepository).flush();
        verify(blockRelationshipQueryService, never())
                .existsBlockBetween(any(), any());
        verify(friendshipRepository, never())
                .existsByPetLowIdAndPetHighId(any(), any());
        verify(friendshipRepository, never())
                .countRelationshipsByPetIds(any());
        verify(acceptanceService, never()).accept(any(), any(), any());
    }

    @Test
    void cancelsAsRequesterWithoutResponseAssembly() {
        FriendRequest pending = prepareRequester(pending());
        given(clock.instant()).willReturn(NOW);

        FriendRequestActionResult result =
                service.cancel(REQUESTER_USER_ID, REQUEST_ID);

        assertThat(result).isEqualTo(Terminal.CANCELED);
        assertThat(pending.getStatus()).isEqualTo(FriendRequestStatus.CANCELED);
        assertThat(pending.getRespondedAt()).isEqualTo(NOW);
        verify(friendRequestRepository).flush();
        verify(blockRelationshipQueryService, never())
                .existsBlockBetween(any(), any());
        verify(friendshipRepository, never())
                .existsByPetLowIdAndPetHighId(any(), any());
        verify(acceptanceService, never()).accept(any(), any(), any());
        verifyNoInteractions(responseAssembler);
    }

    @Test
    void commitsExpiredOutcomeWithoutCallingAcceptanceDependencies() {
        FriendRequest expired = FriendRequest.createPending(
                REQUESTER_PET_ID,
                TARGET_PET_ID,
                NOW.minusSeconds(120),
                NOW
        );
        prepareTarget(expired);
        given(clock.instant()).willReturn(NOW);

        FriendRequestActionResult result =
                service.accept(TARGET_USER_ID, REQUEST_ID);

        assertThat(result).isEqualTo(Terminal.EXPIRED);
        assertThat(expired.getStatus()).isEqualTo(FriendRequestStatus.EXPIRED);
        assertThat(expired.getRespondedAt()).isNull();
        verify(friendRequestRepository).flush();
        verify(friendshipRepository, never())
                .existsByPetLowIdAndPetHighId(any(), any());
        verify(acceptanceService, never()).accept(any(), any(), any());
        verify(responseAssembler, never()).accepted(any(), any(), any());
    }

    @Test
    void hidesMissingAndUnauthorizedRequestBeforePairLock() {
        given(activePetQueryService.requireActivePet(TARGET_USER_ID))
                .willReturn(activePet(TARGET_PET_ID, TARGET_USER_ID));
        given(friendRequestRepository.findPairById(REQUEST_ID))
                .willReturn(Optional.empty());

        assertError(
                () -> service.accept(TARGET_USER_ID, REQUEST_ID),
                ErrorCode.FRIEND_REQUEST_NOT_FOUND
        );

        verify(interactionPairLockService, never())
                .lockInteractionPair(any(), any());
        verify(friendRequestRepository, never())
                .findByIdForUpdate(any());
    }

    @Test
    void keepsBlockPrecedenceForAccept() {
        FriendRequest accepted = pending();
        accepted.accept(NOW.minusSeconds(1));
        prepareTarget(accepted);
        given(blockRelationshipQueryService.existsBlockBetween(
                REQUESTER_USER_ID,
                TARGET_USER_ID
        )).willReturn(true);

        assertError(
                () -> service.accept(TARGET_USER_ID, REQUEST_ID),
                ErrorCode.BLOCKED_USER
        );

        verify(clock, never()).instant();
        verify(friendshipRepository, never())
                .existsByPetLowIdAndPetHighId(any(), any());
    }

    @Test
    void rejectsNonPendingCleanupActionWithoutClockRead() {
        FriendRequest accepted = pending();
        accepted.accept(NOW.minusSeconds(1));
        prepareTarget(accepted);

        assertError(
                () -> service.reject(TARGET_USER_ID, REQUEST_ID),
                ErrorCode.FRIEND_REQUEST_NOT_PENDING
        );

        verify(clock, never()).instant();
        verify(friendRequestRepository, never()).flush();
    }

    private FriendRequest prepareTarget(FriendRequest request) {
        prepare(
                request,
                TARGET_USER_ID,
                TARGET_PET_ID
        );
        return request;
    }

    private FriendRequest prepareRequester(FriendRequest request) {
        prepare(
                request,
                REQUESTER_USER_ID,
                REQUESTER_PET_ID
        );
        return request;
    }

    private void prepare(
            FriendRequest request,
            Long actorUserId,
            Long actorPetId
    ) {
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        FriendRequestPairRow row = org.mockito.Mockito.mock(
                FriendRequestPairRow.class
        );
        given(row.getRequestId()).willReturn(REQUEST_ID);
        given(row.getRequesterPetId()).willReturn(REQUESTER_PET_ID);
        given(row.getTargetPetId()).willReturn(TARGET_PET_ID);
        given(activePetQueryService.requireActivePet(actorUserId))
                .willReturn(activePet(actorPetId, actorUserId));
        given(friendRequestRepository.findPairById(REQUEST_ID))
                .willReturn(Optional.of(row));
        given(interactionPairLockService.lockInteractionPair(
                REQUESTER_PET_ID,
                TARGET_PET_ID
        )).willReturn(pair());
        given(friendRequestRepository.findByIdForUpdate(REQUEST_ID))
                .willReturn(Optional.of(request));
    }

    private FriendRequest pending() {
        return FriendRequest.createPending(
                REQUESTER_PET_ID,
                TARGET_PET_ID,
                NOW.minusSeconds(60),
                NOW.plusSeconds(60)
        );
    }

    private ActivePetContext activePet(Long petId, Long userId) {
        return new ActivePetContext(
                petId,
                userId,
                "actor#tag",
                "actor",
                null,
                true
        );
    }

    private InteractionPairContext pair() {
        return new InteractionPairContext(
                new LockedUserContext(
                        REQUESTER_USER_ID,
                        AccountStatus.ACTIVE,
                        REQUESTER_PET_ID,
                        "requester#tag"
                ),
                new LockedUserContext(
                        TARGET_USER_ID,
                        AccountStatus.ACTIVE,
                        TARGET_PET_ID,
                        "target#tag"
                ),
                new LockedPetContext(
                        REQUESTER_PET_ID,
                        REQUESTER_USER_ID,
                        PetStatus.ACTIVE,
                        null
                ),
                new LockedPetContext(
                        TARGET_PET_ID,
                        TARGET_USER_ID,
                        PetStatus.ACTIVE,
                        null
                )
        );
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
}
